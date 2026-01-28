package com.binance.index.service;

import com.binance.index.dto.CoinPriceDTO;
import com.binance.index.dto.DistributionBucket;
import com.binance.index.dto.DistributionData;

import com.binance.index.dto.KlineData;
import com.binance.index.dto.UptrendData;
import com.binance.index.dto.BacktestMonthlyResult;
import com.binance.index.entity.BasePrice;
import com.binance.index.entity.CoinPrice;
import com.binance.index.entity.MarketIndex;
import com.binance.index.repository.BasePriceRepository;
import com.binance.index.repository.CoinPriceRepository;
import com.binance.index.repository.JdbcCoinPriceRepository;
import com.binance.index.repository.MarketIndexRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 指数计算服务
 */
@Service
@Slf4j
public class IndexCalculatorService {

    private static final ZoneId UTC_ZONE = ZoneId.of("UTC");

    private final BinanceApiService binanceApiService;
    private final MarketIndexRepository marketIndexRepository;
    private final CoinPriceRepository coinPriceRepository;
    private final JdbcCoinPriceRepository jdbcCoinPriceRepository;
    private final BasePriceRepository basePriceRepository;
    private final ExecutorService executorService;

    @Autowired(required = false)
    private KlineService klineService;

    public BinanceApiService getBinanceApiService() {
        return binanceApiService;
    }

    public KlineService getKlineService() {
        return klineService;
    }

    // 缓存各币种的基准价格（回补起始时间的价格）
    private Map<String, Double> basePrices = new HashMap<>();
    private LocalDateTime basePriceTime;

    // 单边上行数据缓存（5分钟过期，最多缓存10个不同参数的结果）
    private final Cache<String, UptrendData> uptrendCache = Caffeine.newBuilder()
            .maximumSize(10)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    // 回测价格缓存：用于优化器并行运行时复用相同时间点的价格查询
    // 1分钟过期，足以支撑一次优化任务的完成
    private final Cache<LocalDateTime, List<CoinPrice>> backtestPriceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    // 波段计算专用线程池（使用4线程避免CPU过载）
    private final java.util.concurrent.ForkJoinPool waveCalculationPool = new java.util.concurrent.ForkJoinPool(4);

    // 打印线程池状态的辅助方法
    private void logPoolStatus(String phase) {
        log.info("[POOL-STATUS][{}] 活跃线程={}, 运行线程={}, 队列任务={}, 窃取任务={}, CPU核心={}",
                phase,
                waveCalculationPool.getActiveThreadCount(),
                waveCalculationPool.getRunningThreadCount(),
                waveCalculationPool.getQueuedTaskCount(),
                waveCalculationPool.getStealCount(),
                Runtime.getRuntime().availableProcessors());
    }

    // 回补状态标志
    private volatile boolean backfillInProgress = false;

    // 回补期间的缓冲队列：暂存实时采集的K线原始数据，回补完成后再计算并保存
    private final ConcurrentLinkedQueue<BufferedKlineData> pendingKlineQueue = new ConcurrentLinkedQueue<>();

    // 内部类：用于暂存原始K线数据（不计算指数，等回补完成后再计算）
    private static class BufferedKlineData {
        final LocalDateTime timestamp;
        final List<KlineData> klines;

        BufferedKlineData(LocalDateTime timestamp, List<KlineData> klines) {
            this.timestamp = timestamp;
            this.klines = klines;
        }
    }

    public IndexCalculatorService(BinanceApiService binanceApiService,
            MarketIndexRepository marketIndexRepository,
            CoinPriceRepository coinPriceRepository,
            JdbcCoinPriceRepository jdbcCoinPriceRepository,
            BasePriceRepository basePriceRepository,
            ExecutorService klineExecutorService) {
        this.binanceApiService = binanceApiService;
        this.marketIndexRepository = marketIndexRepository;
        this.coinPriceRepository = coinPriceRepository;
        this.jdbcCoinPriceRepository = jdbcCoinPriceRepository;
        this.basePriceRepository = basePriceRepository;
        this.executorService = klineExecutorService;
    }

    /**
     * 设置回补状态
     */
    public void setBackfillInProgress(boolean inProgress) {
        this.backfillInProgress = inProgress;
    }

    /**
     * 检查回补是否正在进行
     */
    public boolean isBackfillInProgress() {
        return backfillInProgress;
    }

    /**
     * 清理数据库中的重复数据（启动时调用）
     * 删除 CoinPrice 和 MarketIndex 表中的重复记录，保留每组重复数据中 id 最小的一条
     */
    @org.springframework.transaction.annotation.Transactional
    public void cleanupDuplicateData() {
        log.info("开始检查并清理重复数据...");

        try {
            // 统计 CoinPrice 重复数据
            String countCoinPriceSql = "SELECT COUNT(*) FROM coin_price cp1 WHERE EXISTS (" +
                    "SELECT 1 FROM coin_price cp2 WHERE cp1.symbol = cp2.symbol AND cp1.timestamp = cp2.timestamp AND cp1.id > cp2.id)";
            Long duplicateCoinPriceCount = jdbcCoinPriceRepository.getJdbcTemplate().queryForObject(countCoinPriceSql,
                    Long.class);

            if (duplicateCoinPriceCount != null && duplicateCoinPriceCount > 0) {
                log.warn("发现 {} 条重复的 CoinPrice 数据，开始清理...", duplicateCoinPriceCount);

                // 删除 CoinPrice 重复数据（保留 id 最小的）
                String deleteCoinPriceSql = "DELETE FROM coin_price WHERE id IN (" +
                        "SELECT cp1.id FROM coin_price cp1 WHERE EXISTS (" +
                        "SELECT 1 FROM coin_price cp2 WHERE cp1.symbol = cp2.symbol AND cp1.timestamp = cp2.timestamp AND cp1.id > cp2.id))";
                int deletedCoinPrice = jdbcCoinPriceRepository.getJdbcTemplate().update(deleteCoinPriceSql);
                log.info("已删除 {} 条重复的 CoinPrice 数据", deletedCoinPrice);
            } else {
                log.info("CoinPrice 表无重复数据");
            }

            // 统计 MarketIndex 重复数据
            String countMarketIndexSql = "SELECT COUNT(*) FROM market_index mi1 WHERE EXISTS (" +
                    "SELECT 1 FROM market_index mi2 WHERE mi1.timestamp = mi2.timestamp AND mi1.id > mi2.id)";
            Long duplicateMarketIndexCount = jdbcCoinPriceRepository.getJdbcTemplate()
                    .queryForObject(countMarketIndexSql, Long.class);

            if (duplicateMarketIndexCount != null && duplicateMarketIndexCount > 0) {
                log.warn("发现 {} 条重复的 MarketIndex 数据，开始清理...", duplicateMarketIndexCount);

                // 删除 MarketIndex 重复数据（保留 id 最小的）
                String deleteMarketIndexSql = "DELETE FROM market_index WHERE id IN (" +
                        "SELECT mi1.id FROM market_index mi1 WHERE EXISTS (" +
                        "SELECT 1 FROM market_index mi2 WHERE mi1.timestamp = mi2.timestamp AND mi1.id > mi2.id))";
                int deletedMarketIndex = jdbcCoinPriceRepository.getJdbcTemplate().update(deleteMarketIndexSql);
                log.info("已删除 {} 条重复的 MarketIndex 数据", deletedMarketIndex);
            } else {
                log.info("MarketIndex 表无重复数据");
            }

            log.info("重复数据清理完成");
        } catch (Exception e) {
            log.error("清理重复数据失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 清空单边上行缓存（在新数据采集后调用）
     */
    public void invalidateUptrendCache() {
        uptrendCache.invalidateAll();
        log.debug("单边上行缓存已清空");
    }

    /**
     * 清理下架币种的基准价格（只删除基准价格，保留历史数据用于涨幅分布和单边分析）
     * 当币种重新上架时，会被当作新币处理，重新设置基准价格
     *
     * @param activeSymbols 当前正在交易的币种列表（从exchangeInfo获取）
     */
    @org.springframework.transaction.annotation.Transactional
    public void cleanupDelistedCoins(Set<String> activeSymbols) {
        if (activeSymbols == null || activeSymbols.isEmpty()) {
            return;
        }

        // 获取数据库中已存储的所有币种基准价格
        Set<String> storedSymbols = basePriceRepository.findAll()
                .stream()
                .map(BasePrice::getSymbol)
                .collect(Collectors.toSet());

        // 求差集：数据库有但API没返回的 = 已下架
        Set<String> delistedSymbols = new HashSet<>(storedSymbols);
        delistedSymbols.removeAll(activeSymbols);

        if (delistedSymbols.isEmpty()) {
            return;
        }

        log.warn("检测到 {} 个币种已下架，清理基准价格（保留历史数据）: {}", delistedSymbols.size(), delistedSymbols);

        for (String symbol : delistedSymbols) {
            // 只删除基准价格（不删除历史价格，保留用于涨幅分布和单边分析）
            basePriceRepository.deleteBySymbol(symbol);

            // 清理内存缓存
            basePrices.remove(symbol);

            log.warn("已清理下架币种 {} 的基准价格（历史数据已保留），重新上架时将重新设置基准价格", symbol);
        }

        log.warn("下架币种基准价格清理完成，共清理 {} 个币种", delistedSymbols.size());
    }

    /**
     * 计算并保存当前时刻的市场指数（实时采集用）
     * 使用并发获取K线数据，获取准确的5分钟成交额
     */
    @org.springframework.transaction.annotation.Transactional
    public MarketIndex calculateAndSaveCurrentIndex() {
        // 如果没有基准价格，需要先刷新
        if (basePrices.isEmpty()) {
            log.warn("基准价格为空，等待回补完成或手动刷新");
            return null;
        }

        // 预测最新闭合K线的时间：当前时间对齐到5分钟后减5分钟
        // 例如：09:07 → 对齐到09:05 → 减5分钟 → 09:00（这是最新闭合K线的openTime）
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime expectedKlineTime = alignToFiveMinutes(now).minusMinutes(5);

        // 检查是否已存在该时间点的数据（在调用API之前先检查，避免浪费资源）
        if (marketIndexRepository.existsByTimestamp(expectedKlineTime)) {
            log.debug("时间点 {} 已存在数据，跳过采集", expectedKlineTime);
            return null;
        }

        // 获取所有需要处理的币种
        List<String> symbols = binanceApiService.getAllUsdtSymbols();
        if (symbols.isEmpty()) {
            log.warn("无法获取交易对列表");
            return null;
        }

        // 清理已下架币种的数据（对比当前活跃币种和数据库中的币种）
        cleanupDelistedCoins(new HashSet<>(symbols));

        log.info("开始并发获取 {} 个币种的K线数据...", symbols.size());
        long startTime = System.currentTimeMillis();

        // 并发获取所有币种的最新K线
        List<CompletableFuture<KlineData>> futures = symbols.stream()
                .map(symbol -> CompletableFuture.supplyAsync(
                        () -> binanceApiService.getLatestKline(symbol),
                        executorService))
                .collect(Collectors.toList());

        // 等待所有请求完成
        List<KlineData> allKlines = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("K线数据获取完成，成功 {} 个，耗时 {}ms", allKlines.size(), elapsed);

        if (allKlines.isEmpty()) {
            log.warn("无有效K线数据");
            return null;
        }

        // 使用K线本身的timestamp（所有K线应该是同一时间点的）
        LocalDateTime klineTime = allKlines.get(0).getTimestamp();

        // 再次检查是否已存在该时间点的数据（双重检查，防止并发）
        if (marketIndexRepository.existsByTimestamp(klineTime)) {
            log.debug("时间点 {} 已存在数据，跳过", klineTime);
            return null;
        }

        // 计算指数和成交额
        double totalChange = 0;
        double totalVolume = 0;
        int validCount = 0;
        int upCount = 0; // 上涨币种数
        int downCount = 0; // 下跌币种数

        for (KlineData kline : allKlines) {
            String symbol = kline.getSymbol();
            Double basePrice = basePrices.get(symbol);

            // 新币处理：如果没有基准价格，使用当前价格作为基准并保存到数据库
            if (basePrice == null || basePrice <= 0) {
                if (kline.getClosePrice() > 0) {
                    basePrices.put(symbol, kline.getClosePrice());
                    // 同时保存到数据库
                    basePriceRepository.save(new BasePrice(symbol, kline.getClosePrice()));
                    log.info("新币种 {} 设置基准价格: {} (已保存到数据库)", symbol, kline.getClosePrice());
                }
                continue; // 第一次采集时跳过计算，下次开始参与
            }

            // 计算相对于基准时间的涨跌幅
            double changePercent = (kline.getClosePrice() - basePrice) / basePrice * 100;
            double volume = kline.getVolume(); // 5分钟成交额

            // 统计涨跌数量
            if (changePercent > 0) {
                upCount++;
            } else if (changePercent < 0) {
                downCount++;
            }

            totalChange += changePercent;
            totalVolume += volume;
            validCount++;
        }

        if (validCount == 0) {
            log.warn("无有效数据计算指数");
            return null;
        }

        // 简单平均
        double indexValue = totalChange / validCount;

        // 计算涨跌比率
        double adr = downCount > 0 ? (double) upCount / downCount : upCount;

        // 再次检查是否已存在该时间点的数据（双重检查）
        if (marketIndexRepository.existsByTimestamp(klineTime)) {
            log.debug("时间点 {} 已存在数据（并发写入），跳过", klineTime);
            return null;
        }

        MarketIndex index = new MarketIndex(klineTime, indexValue, totalVolume, validCount, upCount, downCount, adr);
        marketIndexRepository.save(index);

        // 保存每个币种的OHLC价格（使用K线本身的timestamp）
        List<CoinPrice> coinPrices = allKlines.stream()
                .filter(k -> k.getClosePrice() > 0)
                .map(k -> new CoinPrice(k.getSymbol(), k.getTimestamp(),
                        k.getOpenPrice(), k.getHighPrice(), k.getLowPrice(), k.getClosePrice()))
                .collect(Collectors.toList());
        jdbcCoinPriceRepository.batchInsert(coinPrices);
        log.debug("保存 {} 个币种价格", coinPrices.size());

        // 新数据采集后清空单边上行缓存
        invalidateUptrendCache();

        log.info("保存指数: 时间={}, 值={}%, 涨/跌={}/{}, ADR={}, 币种数={}",
                klineTime, String.format("%.4f", indexValue),
                upCount, downCount, String.format("%.2f", adr), validCount);
        return index;
    }

    /**
     * 回补期间采集数据并暂存到内存队列（只保存原始K线，不计算指数）
     * 等回补完成后，基准价格设置好了再计算指数并保存
     */
    public void collectAndBuffer() {
        // 预测最新闭合K线的时间
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime expectedKlineTime = alignToFiveMinutes(now).minusMinutes(5);

        // 检查是否已存在该时间点的数据（避免重复采集）
        if (marketIndexRepository.existsByTimestamp(expectedKlineTime)) {
            log.debug("时间点 {} 已存在数据，跳过暂存采集", expectedKlineTime);
            return;
        }

        // 检查队列中是否已有该时间点的数据（避免重复暂存）
        for (BufferedKlineData buffered : pendingKlineQueue) {
            if (buffered.timestamp.equals(expectedKlineTime)) {
                log.debug("时间点 {} 已在暂存队列中，跳过", expectedKlineTime);
                return;
            }
        }

        // 获取所有需要处理的币种
        List<String> symbols = binanceApiService.getAllUsdtSymbols();
        if (symbols.isEmpty()) {
            log.warn("无法获取交易对列表");
            return;
        }

        log.info("回补期间暂存采集：开始获取 {} 个币种的K线数据...", symbols.size());
        long startTime = System.currentTimeMillis();

        // 并发获取所有币种的最新K线
        List<CompletableFuture<KlineData>> futures = symbols.stream()
                .map(symbol -> CompletableFuture.supplyAsync(
                        () -> binanceApiService.getLatestKline(symbol),
                        executorService))
                .collect(Collectors.toList());

        // 等待所有请求完成
        List<KlineData> allKlines = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("暂存采集K线数据完成，成功 {} 个，耗时 {}ms", allKlines.size(), elapsed);

        if (allKlines.isEmpty()) {
            log.warn("无有效K线数据");
            return;
        }

        // 使用K线本身的timestamp
        LocalDateTime klineTime = allKlines.get(0).getTimestamp();

        // 再次检查（双重检查，防止并发）
        if (marketIndexRepository.existsByTimestamp(klineTime)) {
            log.debug("时间点 {} 已存在数据，跳过暂存", klineTime);
            return;
        }

        // 检查队列中是否已有该时间点
        for (BufferedKlineData buffered : pendingKlineQueue) {
            if (buffered.timestamp.equals(klineTime)) {
                log.debug("时间点 {} 已在暂存队列中，跳过", klineTime);
                return;
            }
        }

        // 只暂存原始K线数据，不计算指数（因为基准价格可能还没设置好）
        pendingKlineQueue.offer(new BufferedKlineData(klineTime, allKlines));
        log.info("K线数据已暂存: 时间={}, 队列大小={}", klineTime, pendingKlineQueue.size());
    }

    /**
     * 将暂存队列中的K线数据计算指数后保存到数据库（回补完成后调用）
     * 此时基准价格已经设置好，可以正确计算指数
     */
    public void flushPendingData() {
        if (pendingKlineQueue.isEmpty()) {
            log.info("暂存队列为空，无需刷新");
            return;
        }

        log.info("开始处理 {} 条暂存K线数据...", pendingKlineQueue.size());
        int savedIndexCount = 0;
        int savedPriceCount = 0;
        int skippedCount = 0;

        BufferedKlineData bufferedData;
        while ((bufferedData = pendingKlineQueue.poll()) != null) {
            LocalDateTime timestamp = bufferedData.timestamp;

            // 检查是否已存在（避免与回补数据重复）
            if (marketIndexRepository.existsByTimestamp(timestamp)) {
                log.debug("时间点 {} 已存在（回补数据），跳过暂存数据", timestamp);
                skippedCount++;
                continue;
            }

            // 使用当前的基准价格计算指数
            List<KlineData> allKlines = bufferedData.klines;

            double totalChange = 0;
            double totalVolume = 0;
            int validCount = 0;
            int upCount = 0;
            int downCount = 0;
            List<CoinPrice> coinPrices = new ArrayList<>();

            for (KlineData kline : allKlines) {
                String symbol = kline.getSymbol();
                Double basePrice = basePrices.get(symbol);

                if (basePrice == null || basePrice <= 0) {
                    continue;
                }

                double changePercent = (kline.getClosePrice() - basePrice) / basePrice * 100;
                double volume = kline.getVolume();

                if (changePercent > 0) {
                    upCount++;
                } else if (changePercent < 0) {
                    downCount++;
                }

                totalChange += changePercent;
                totalVolume += volume;
                validCount++;

                if (kline.getClosePrice() > 0) {
                    coinPrices.add(new CoinPrice(kline.getSymbol(), kline.getTimestamp(),
                            kline.getOpenPrice(), kline.getHighPrice(), kline.getLowPrice(), kline.getClosePrice()));
                }
            }

            if (validCount == 0) {
                log.warn("暂存数据 {} 无有效币种计算指数，跳过", timestamp);
                skippedCount++;
                continue;
            }

            double indexValue = totalChange / validCount;
            double adr = downCount > 0 ? (double) upCount / downCount : upCount;
            MarketIndex index = new MarketIndex(timestamp, indexValue, totalVolume, validCount, upCount, downCount,
                    adr);

            // 保存指数
            marketIndexRepository.save(index);
            savedIndexCount++;

            // 保存币种价格
            if (!coinPrices.isEmpty()) {
                jdbcCoinPriceRepository.batchInsert(coinPrices);
                savedPriceCount += coinPrices.size();
            }

            log.debug("暂存数据已保存: 时间={}", timestamp);
        }

        log.info("暂存数据刷新完成: 保存指数 {} 条, 价格 {} 条, 跳过 {} 条（已存在或无效）",
                savedIndexCount, savedPriceCount, skippedCount);
    }

    /**
     * 回补历史数据
     *
     * @param days 回补天数
     */
    public void backfillHistoricalData(int days) {
        log.info("开始回补 {} 天历史数据...", days);

        // 首先尝试从数据库加载基准价格
        List<BasePrice> existingBasePrices = basePriceRepository.findAll();
        if (!existingBasePrices.isEmpty()) {
            basePrices = existingBasePrices.stream()
                    .collect(Collectors.toMap(BasePrice::getSymbol, BasePrice::getPrice, (a, b) -> a));
            basePriceTime = existingBasePrices.get(0).getCreatedAt();
            log.info("从数据库加载基准价格成功，共 {} 个币种，创建时间: {}", basePrices.size(), basePriceTime);
        } else {
            log.info("数据库中没有基准价格，将从历史数据计算");
        }

        // 查询数据库最晚时间点
        LocalDateTime dbLatest = coinPriceRepository.findLatestTimestamp();

        List<String> symbols = binanceApiService.getAllUsdtSymbols();
        long now = System.currentTimeMillis();

        // 计算最新闭合K线时间：对齐到5分钟边界再减5分钟
        // 例如：当前 14:22 → 对齐到14:20 → 减5分钟 → 14:15（这是最新闭合K线的openTime）
        long fiveMinutesMs = 5 * 60 * 1000;
        long alignedNow = (now / fiveMinutesMs) * fiveMinutesMs;
        long latestClosedKlineMs = alignedNow - fiveMinutesMs;
        LocalDateTime latestClosedKline = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(latestClosedKlineMs), ZoneId.of("UTC"));

        long startTime;
        long alignedEndTime = latestClosedKlineMs;

        if (dbLatest == null) {
            // 数据库为空，回补 N 天
            startTime = latestClosedKlineMs - (long) days * 24 * 60 * 60 * 1000;
            log.info("数据库为空，回补 {} 天数据", days);
        } else if (!dbLatest.isBefore(latestClosedKline)) {
            // 数据库已是最新，跳过回补
            log.info("数据库已是最新（dbLatest={}, latestClosedKline={}），跳过API回补", dbLatest, latestClosedKline);
            return;
        } else {
            // 增量回补：从 dbLatest + 5min 开始
            LocalDateTime incrementalStart = dbLatest.plusMinutes(5);
            startTime = incrementalStart.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();
            log.info("增量回补模式：从 {} 到 {} (dbLatest={})", incrementalStart, latestClosedKline, dbLatest);
        }

        log.info("回补时间范围: {} -> {} (最新闭合K线)",
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startTime), ZoneId.of("UTC")),
                latestClosedKline);

        // 存储每个时间点的所有币种数据: timestamp -> (symbol -> KlineData)
        Map<Long, Map<String, KlineData>> timeSeriesData = new TreeMap<>();

        int processedCount = 0;
        int failedCount = 0;
        for (String symbol : symbols) {
            try {
                List<KlineData> klines = binanceApiService.getKlinesWithPagination(
                        symbol, "5m", startTime, alignedEndTime, 500);

                for (KlineData kline : klines) {
                    long timestamp = kline.getTimestamp()
                            .atZone(ZoneId.of("UTC"))
                            .toInstant()
                            .toEpochMilli();

                    timeSeriesData.computeIfAbsent(timestamp, k -> new HashMap<>())
                            .put(symbol, kline);
                }

                processedCount++;
                if (processedCount % 50 == 0) {
                    log.info("已处理 {}/{} 个币种（失败：{}）", processedCount, symbols.size(), failedCount);
                }

            } catch (Exception e) {
                failedCount++;
                log.error("获取K线失败 {}: {} - {}", symbol, e.getClass().getSimpleName(), e.getMessage());
                log.debug("详细错误堆栈：", e);

                // 如果连续失败多次，可能是被限流，等待更长时间
                if (failedCount % 10 == 0) {
                    log.warn("连续失败 {} 次，等待5秒后继续...", failedCount);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.info("K线数据获取完成，成功：{}，失败：{}，共 {} 个时间点",
                processedCount, failedCount, timeSeriesData.size());

        // 计算每个时间点的指数
        // 需要先确定基准价格：为每个币种找最早出现的价格（处理新币种）
        Map<String, Double> historicalBasePrices = new HashMap<>();

        // 遍历所有时间点（按时间顺序），为每个币种找最早出现的价格
        for (Map<String, KlineData> symbolData : timeSeriesData.values()) {
            for (Map.Entry<String, KlineData> entry : symbolData.entrySet()) {
                String symbol = entry.getKey();
                // 如果还没有这个币种的基准价格，就用这个（它是最早的）
                if (!historicalBasePrices.containsKey(symbol)) {
                    historicalBasePrices.put(symbol, entry.getValue().getOpenPrice());
                }
            }
        }

        // 更新全局基准价格并保存到数据库
        boolean basePricesWereLoaded = !existingBasePrices.isEmpty();

        if (!basePricesWereLoaded && !historicalBasePrices.isEmpty()) {
            // 场景1：数据库没有基准价格（首次运行），使用本次回补数据初始化
            basePrices = new HashMap<>(historicalBasePrices);
            basePriceTime = LocalDateTime.now();

            List<BasePrice> basePriceList = historicalBasePrices.entrySet().stream()
                    .map(e -> new BasePrice(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            basePriceRepository.saveAll(basePriceList);
            log.info("基准价格已初始化并保存到数据库，共 {} 个币种", basePriceList.size());
        } else if (basePricesWereLoaded) {
            // 场景2：数据库有基准价格，检查是否有新币需要添加
            Set<String> newSymbols = new HashSet<>(historicalBasePrices.keySet());
            newSymbols.removeAll(basePrices.keySet());

            if (!newSymbols.isEmpty()) {
                // 将新币的基准价格添加到内存缓存
                for (String symbol : newSymbols) {
                    basePrices.put(symbol, historicalBasePrices.get(symbol));
                }

                // 保存新币基准价格到数据库
                List<BasePrice> newBasePriceList = newSymbols.stream()
                        .map(s -> new BasePrice(s, historicalBasePrices.get(s)))
                        .collect(Collectors.toList());
                basePriceRepository.saveAll(newBasePriceList);
                log.info("新币基准价格已保存: {} 个, 币种={}", newSymbols.size(), newSymbols);
            }
            log.info("使用数据库中的基准价格，共 {} 个币种（含新增 {} 个）", basePrices.size(), newSymbols.size());
        }

        // 计算每个时间点的指数
        List<MarketIndex> indexList = new ArrayList<>();

        // 批量查询已存在的时间戳（优化：1次查询替代2000+次查询）
        LocalDateTime backfillStart = LocalDateTime.now().minusDays(days);
        LocalDateTime backfillEnd = LocalDateTime.now();
        Set<LocalDateTime> existingIndexTimestamps = new HashSet<>(
                marketIndexRepository.findAllTimestampsBetween(backfillStart, backfillEnd));
        Set<LocalDateTime> existingPriceTimestamps = new HashSet<>(
                coinPriceRepository.findAllDistinctTimestampsBetween(backfillStart, backfillEnd));
        log.info("已存在指数时间点: {} 个, 价格时间点: {} 个",
                existingIndexTimestamps.size(), existingPriceTimestamps.size());

        for (Map.Entry<Long, Map<String, KlineData>> entry : timeSeriesData.entrySet()) {
            LocalDateTime timestamp = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(entry.getKey()),
                    ZoneId.of("UTC"));

            // 跳过已存在的数据（使用内存Set快速判断）
            if (existingIndexTimestamps.contains(timestamp)) {
                continue;
            }

            Map<String, KlineData> symbolData = entry.getValue();

            double totalChange = 0;
            double totalVolume = 0;
            int validCount = 0;
            int upCount = 0;
            int downCount = 0;

            for (Map.Entry<String, KlineData> klineEntry : symbolData.entrySet()) {
                String symbol = klineEntry.getKey();
                KlineData kline = klineEntry.getValue();
                Double basePrice = basePrices.get(symbol);

                if (basePrice == null || basePrice <= 0) {
                    continue;
                }

                double changePercent = (kline.getClosePrice() - basePrice) / basePrice * 100;
                double volume = kline.getVolume();

                // 统计涨跌数量
                if (changePercent > 0) {
                    upCount++;
                } else if (changePercent < 0) {
                    downCount++;
                }

                totalChange += changePercent;
                totalVolume += volume;
                validCount++;
            }

            if (validCount > 0) {
                // 简单平均
                double indexValue = totalChange / validCount;
                double adr = downCount > 0 ? (double) upCount / downCount : upCount;
                indexList.add(new MarketIndex(timestamp, indexValue, totalVolume, validCount, upCount, downCount, adr));
            }
        }

        // 批量保存
        if (!indexList.isEmpty()) {
            marketIndexRepository.saveAll(indexList);
            log.info("历史指数数据回补完成，共保存 {} 条记录", indexList.size());
        } else {
            log.info("无新指数数据需要保存");
        }

        // 保存每个时间点的币种价格
        if (!existingPriceTimestamps.isEmpty()) {
            // 使用方法开头已查询的 dbLatest
            LocalDateTime dbEarliest = coinPriceRepository.findEarliestTimestamp();
            log.info("数据库已有价格数据，最早: {}，最晚: {}，本次回补范围内有 {} 个时间点将跳过",
                    dbEarliest, dbLatest, existingPriceTimestamps.size());
        }
        log.info("开始保存币种价格历史...");
        List<CoinPrice> allCoinPrices = new ArrayList<>();
        for (Map.Entry<Long, Map<String, KlineData>> entry : timeSeriesData.entrySet()) {
            LocalDateTime timestamp = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(entry.getKey()),
                    ZoneId.of("UTC"));

            // 跳过已有价格数据的时间点（使用内存Set快速判断）
            if (existingPriceTimestamps.contains(timestamp)) {
                continue;
            }

            for (Map.Entry<String, KlineData> klineEntry : entry.getValue().entrySet()) {
                KlineData kline = klineEntry.getValue();
                if (kline.getClosePrice() > 0) {
                    allCoinPrices.add(new CoinPrice(kline.getSymbol(), timestamp,
                            kline.getOpenPrice(), kline.getHighPrice(), kline.getLowPrice(), kline.getClosePrice()));
                }
            }

            // 每1万条批量保存一次（JDBC批量插入更高效）
            if (allCoinPrices.size() >= 10000) {
                jdbcCoinPriceRepository.batchInsert(allCoinPrices);
                allCoinPrices.clear();
            }
        }
        // 保存剩余的
        if (!allCoinPrices.isEmpty()) {
            jdbcCoinPriceRepository.batchInsert(allCoinPrices);
            log.info("币种价格保存完成");
        }
    }

    /**
     * 获取历史指数数据
     */
    public List<MarketIndex> getHistoryData(int hours) {
        LocalDateTime start = LocalDateTime.now().minusHours(hours);
        return marketIndexRepository.findByTimestampAfterOrderByTimestampAsc(start);
    }

    /**
     * 获取最新指数
     */
    public MarketIndex getLatestIndex() {
        return marketIndexRepository.findTopByOrderByTimestampDesc().orElse(null);
    }

    /**
     * 查询指定币种的历史价格数据（调试用）
     */
    public List<CoinPrice> getCoinPriceHistory(String symbol, LocalDateTime startTime) {
        return coinPriceRepository.findBySymbolAndTimeRange(symbol, startTime);
    }

    /**
     * 获取所有基准价格（调试用）
     */
    public List<BasePrice> getAllBasePrices() {
        return basePriceRepository.findAll();
    }

    /**
     * 获取指定时间之后最早的价格数据（用于debug接口）
     */
    public List<CoinPrice> getEarliestPricesAfter(LocalDateTime startTime) {
        return coinPriceRepository.findEarliestPricesAfter(startTime);
    }

    /**
     * 获取指定时间之前最晚的价格数据（用于debug接口）
     */
    public List<CoinPrice> getLatestPricesBefore(LocalDateTime endTime) {
        return coinPriceRepository.findLatestPricesBefore(endTime);
    }

    /**
     * 验证指数计算（用于debug接口）
     * 使用全局基准价格和最新数据库价格计算，返回详细信息
     */
    public Map<String, Object> verifyIndexCalculation() {
        Map<String, Object> response = new HashMap<>();

        // 获取全局基准价格
        if (basePrices == null || basePrices.isEmpty()) {
            response.put("success", false);
            response.put("message", "全局基准价格未初始化");
            return response;
        }

        // 获取基准价格创建时间
        List<BasePrice> dbBasePrices = basePriceRepository.findAll();
        LocalDateTime basePriceCreatedAt = null;
        if (!dbBasePrices.isEmpty()) {
            basePriceCreatedAt = dbBasePrices.get(0).getCreatedAt();
        }

        // 获取数据库最新价格
        List<CoinPrice> latestPrices = coinPriceRepository.findLatestPrices();
        if (latestPrices.isEmpty()) {
            response.put("success", false);
            response.put("message", "数据库中没有最新价格数据");
            return response;
        }

        LocalDateTime latestPriceTime = latestPrices.get(0).getTimestamp();

        response.put("basePriceTime", basePriceCreatedAt != null ? basePriceCreatedAt.toString() : "未知");
        response.put("latestPriceTime", latestPriceTime.toString());
        response.put("basePriceCount", basePrices.size());
        response.put("latestPriceCount", latestPrices.size());

        // 转换最新价格为Map
        Map<String, Double> latestPriceMap = latestPrices.stream()
                .collect(Collectors.toMap(CoinPrice::getSymbol, CoinPrice::getPrice, (a, b) -> a));

        // 计算每个币种的涨跌幅
        List<Map<String, Object>> coinDetails = new ArrayList<>();
        double totalChange = 0;
        int validCount = 0;
        int upCount = 0;
        int downCount = 0;

        for (Map.Entry<String, Double> entry : latestPriceMap.entrySet()) {
            String symbol = entry.getKey();
            Double latestPrice = entry.getValue();
            Double basePrice = basePrices.get(symbol);

            if (basePrice != null && basePrice > 0 && latestPrice != null && latestPrice > 0) {
                double changePercent = (latestPrice - basePrice) / basePrice * 100;

                Map<String, Object> detail = new HashMap<>();
                detail.put("symbol", symbol);
                detail.put("basePrice", basePrice);
                detail.put("latestPrice", latestPrice);
                detail.put("changePercent", Math.round(changePercent * 10000) / 10000.0);

                coinDetails.add(detail);

                totalChange += changePercent;
                validCount++;
                if (changePercent > 0)
                    upCount++;
                else if (changePercent < 0)
                    downCount++;
            }
        }

        // 按涨跌幅排序
        coinDetails.sort((a, b) -> Double.compare(
                (Double) b.get("changePercent"),
                (Double) a.get("changePercent")));

        // 计算指数（简单平均）
        double calculatedIndex = validCount > 0 ? totalChange / validCount : 0;

        // 获取系统存储的最新指数
        MarketIndex storedIndex = marketIndexRepository.findTopByOrderByTimestampDesc().orElse(null);

        response.put("success", true);
        response.put("totalCoins", validCount);
        response.put("upCount", upCount);
        response.put("downCount", downCount);
        response.put("calculatedIndex", Math.round(calculatedIndex * 10000) / 10000.0);

        if (storedIndex != null) {
            response.put("storedIndex", Math.round(storedIndex.getIndexValue() * 10000) / 10000.0);
            response.put("storedIndexTime", storedIndex.getTimestamp().toString());
            response.put("indexMatch", Math.abs(calculatedIndex - storedIndex.getIndexValue()) < 0.0001);
        }

        response.put("coins", coinDetails);

        return response;
    }

    /**
     * 删除指定时间范围内的数据（用于清理污染数据）
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 删除结果信息
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> deleteDataInRange(LocalDateTime start, LocalDateTime end) {
        Map<String, Object> result = new HashMap<>();

        // 先统计要删除的数量
        long indexCount = marketIndexRepository.countByTimestampBetween(start, end);
        long priceCount = coinPriceRepository.findAllDistinctTimestampsBetween(start, end).size();

        log.info("开始删除数据: {} -> {}", start, end);
        log.info("将删除指数记录: {} 条, 价格时间点: {} 个", indexCount, priceCount);

        // 删除数据
        marketIndexRepository.deleteByTimestampBetween(start, end);
        coinPriceRepository.deleteByTimestampBetween(start, end);

        log.info("数据删除完成");

        result.put("deletedIndexCount", indexCount);
        result.put("deletedPriceTimePoints", priceCount);
        result.put("startTime", start.toString());
        result.put("endTime", end.toString());

        return result;
    }

    /**
     * 清理指定时间范围内的所有数据（用于重新回补前）
     * 删除 CoinPrice 和 MarketIndex，并清空缓存
     *
     * @param start 开始时间 (UTC)
     * @param end   结束时间 (UTC)
     * @return 清理结果
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> cleanupDataInRange(LocalDateTime start, LocalDateTime end) {
        Map<String, Object> result = new HashMap<>();
        long startMs = System.currentTimeMillis();

        log.info("========== 开始清理数据 ==========");
        log.info("时间范围 (UTC): {} -> {}", start, end);

        // 统计并删除 CoinPrice
        String countPriceSql = "SELECT COUNT(*) FROM coin_price WHERE timestamp >= ? AND timestamp <= ?";
        Long priceCount = jdbcCoinPriceRepository.getJdbcTemplate().queryForObject(
                countPriceSql, Long.class, start, end);

        if (priceCount != null && priceCount > 0) {
            String deletePriceSql = "DELETE FROM coin_price WHERE timestamp >= ? AND timestamp <= ?";
            int deleted = jdbcCoinPriceRepository.getJdbcTemplate().update(deletePriceSql, start, end);
            log.info("已删除 {} 条 CoinPrice 数据", deleted);
            result.put("deletedCoinPriceCount", deleted);
        } else {
            log.info("CoinPrice 表无匹配数据");
            result.put("deletedCoinPriceCount", 0);
        }

        // 统计并删除 MarketIndex
        long indexCount = marketIndexRepository.countByTimestampBetween(start, end);
        if (indexCount > 0) {
            marketIndexRepository.deleteByTimestampBetween(start, end);
            log.info("已删除 {} 条 MarketIndex 数据", indexCount);
            result.put("deletedMarketIndexCount", indexCount);
        } else {
            log.info("MarketIndex 表无匹配数据");
            result.put("deletedMarketIndexCount", 0);
        }

        // 清空缓存
        uptrendCache.invalidateAll();
        log.info("已清空单边上行缓存");

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("========== 数据清理完成，耗时 {}ms ==========", elapsed);

        result.put("success", true);
        result.put("timeRange", start + " ~ " + end);
        result.put("elapsedMs", elapsed);
        return result;
    }

    /**
     * 删除指定币种的所有数据（包括历史价格和基准价格）
     *
     * @param symbol 币种符号，如 SOLUSDT
     * @return 删除结果信息
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> deleteSymbolData(String symbol) {
        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);

        // 统计要删除的记录数
        long priceCount = coinPriceRepository.countBySymbol(symbol);
        boolean hasBasePrice = basePrices.containsKey(symbol) || basePriceRepository.existsById(symbol);

        // 删除历史价格
        coinPriceRepository.deleteBySymbol(symbol);

        // 删除基准价格
        basePriceRepository.deleteBySymbol(symbol);

        // 清理内存缓存
        basePrices.remove(symbol);

        log.warn("已删除币种 {} 的所有数据: 历史价格 {} 条, 基准价格 {} 条, 已从内存缓存移除",
                symbol, priceCount, hasBasePrice ? 1 : 0);

        result.put("deletedPriceCount", priceCount);
        result.put("deletedBasePrice", hasBasePrice);
        result.put("success", true);

        return result;
    }

    /**
     * 查询缺漏的历史价格时间点（只查询，不修复）
     * 与修复接口 repairMissingPriceData 类似，但只返回缺漏信息不做任何修改
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 缺漏时间点统计（按币种）
     */
    public Map<String, Object> getMissingTimestamps(LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> result = new HashMap<>();

        log.info("查询各币种缺漏时间点: {} ~ {}", startTime, endTime);

        // 确保只检查已闭合的K线（当前对齐时间 - 5分钟）
        LocalDateTime latestClosedKline = alignToFiveMinutes(LocalDateTime.now(java.time.ZoneOffset.UTC))
                .minusMinutes(5);
        LocalDateTime actualEndTime = endTime.isAfter(latestClosedKline) ? latestClosedKline : endTime;

        log.info("实际检查范围: {} ~ {} (最新闭合K线: {})", startTime, actualEndTime, latestClosedKline);

        // 1. 生成应该存在的所有时间点
        List<LocalDateTime> expectedTimestamps = new ArrayList<>();
        LocalDateTime checkTime = alignToFiveMinutes(startTime);
        while (!checkTime.isAfter(actualEndTime)) {
            expectedTimestamps.add(checkTime);
            checkTime = checkTime.plusMinutes(5);
        }
        int expectedPerCoin = expectedTimestamps.size();

        // 2. 获取所有活跃币种
        List<String> activeSymbols = binanceApiService.getAllUsdtSymbols();

        // 3. 检查每个币种的缺漏情况
        List<Map<String, Object>> symbolsMissing = new ArrayList<>();
        int totalMissing = 0;
        int symbolsWithMissing = 0;

        for (String symbol : activeSymbols) {
            // 获取该币种已有的时间戳
            List<CoinPrice> prices = coinPriceRepository.findBySymbolInRangeOrderByTime(
                    symbol, startTime, endTime);
            Set<LocalDateTime> existingSet = prices.stream()
                    .map(p -> p.getTimestamp().truncatedTo(java.time.temporal.ChronoUnit.MINUTES))
                    .collect(Collectors.toSet());

            // 找出缺漏的时间点
            List<String> missing = new ArrayList<>();
            for (LocalDateTime ts : expectedTimestamps) {
                if (!existingSet.contains(ts)) {
                    missing.add(ts.toString());
                }
            }

            if (!missing.isEmpty()) {
                Map<String, Object> symbolInfo = new HashMap<>();
                symbolInfo.put("symbol", symbol);
                symbolInfo.put("existing", existingSet.size());
                symbolInfo.put("missing", missing.size());
                symbolInfo.put("missingTimestamps", missing.size() <= 10 ? missing : missing.subList(0, 10)); // 最多显示10个
                symbolsMissing.add(symbolInfo);
                totalMissing += missing.size();
                symbolsWithMissing++;
            }
        }

        // 4. 返回结果
        result.put("totalSymbols", activeSymbols.size());
        result.put("expectedPerCoin", expectedPerCoin);
        result.put("symbolsWithMissing", symbolsWithMissing);
        result.put("totalMissingRecords", totalMissing);
        result.put("details", symbolsMissing.size() <= 50 ? symbolsMissing : symbolsMissing.subList(0, 50)); // 最多显示50个币种

        log.info("查询完成: {}个币种, {}个有缺漏, 共缺{}条记录",
                activeSymbols.size(), symbolsWithMissing, totalMissing);

        return result;
    }

    /**
     * 修复所有币种的历史价格缺失数据
     * 检测每个币种在指定时间范围内的数据缺口，并从币安API回补
     *
     * @param startTime 开始时间（可选，为空则使用 days 参数）
     * @param endTime   结束时间（可选，为空则使用当前时间）
     * @param days      检查最近多少天的数据，当 startTime 为空时使用
     * @return 修复结果详情
     */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> repairMissingPriceData(LocalDateTime startTime, LocalDateTime endTime, int days,
            List<String> symbols) {
        Map<String, Object> result = new HashMap<>();
        long totalStartTime = System.currentTimeMillis();

        // 计算时间范围
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime actualStartTime = startTime != null ? startTime : now.minusDays(days);
        LocalDateTime actualEndTime = endTime != null ? endTime : alignToFiveMinutes(now).minusMinutes(5);

        log.info("开始检测并修复历史价格缺失数据: {} ~ {}", actualStartTime, actualEndTime);

        // 获取要修复的币种列表
        List<String> activeSymbols;
        if (symbols != null && !symbols.isEmpty()) {
            // 使用指定的币种
            activeSymbols = symbols;
            log.info("只修复指定的 {} 个币种: {}", activeSymbols.size(), activeSymbols);
        } else {
            // 获取所有活跃币种
            activeSymbols = binanceApiService.getAllUsdtSymbols();
        }
        if (activeSymbols.isEmpty()) {
            result.put("success", false);
            result.put("message", "无法获取活跃币种列表");
            return result;
        }

        log.info("检查 {} 个币种的历史数据完整性...", activeSymbols.size());

        // 【优化1】一次性批量查询所有币种的已有时间戳
        long dbQueryStart = System.currentTimeMillis();
        List<Object[]> allTimestamps = coinPriceRepository.findSymbolTimestampsInRange(actualStartTime, actualEndTime);
        log.info("批量查询完成，耗时 {}ms，共 {} 条记录", System.currentTimeMillis() - dbQueryStart, allTimestamps.size());

        // 按币种分组
        Map<String, Set<LocalDateTime>> existingBySymbol = new HashMap<>();
        for (Object[] row : allTimestamps) {
            String symbol = (String) row[0];
            LocalDateTime timestamp = ((LocalDateTime) row[1]).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            existingBySymbol.computeIfAbsent(symbol, k -> new HashSet<>()).add(timestamp);
        }
        allTimestamps = null; // 帮助 GC

        // 生成应该存在的所有时间点
        Set<LocalDateTime> expectedTimestamps = new HashSet<>();
        LocalDateTime checkTime = alignToFiveMinutes(actualStartTime);
        while (!checkTime.isAfter(actualEndTime)) {
            expectedTimestamps.add(checkTime);
            checkTime = checkTime.plusMinutes(5);
        }
        log.info("应存在 {} 个时间点", expectedTimestamps.size());

        // 找出每个币种的缺失时间段
        Map<String, List<long[]>> symbolMissingRanges = new HashMap<>();
        for (String symbol : activeSymbols) {
            Set<LocalDateTime> existing = existingBySymbol.getOrDefault(symbol, Collections.emptySet());
            List<LocalDateTime> missingTimestamps = expectedTimestamps.stream()
                    .filter(ts -> !existing.contains(ts))
                    .sorted()
                    .collect(Collectors.toList());

            if (!missingTimestamps.isEmpty()) {
                List<long[]> ranges = findMissingRanges(missingTimestamps);
                if (!ranges.isEmpty()) {
                    symbolMissingRanges.put(symbol, ranges);
                }
            }
        }

        log.info("发现 {} 个币种有缺失数据，开始并行修复...", symbolMissingRanges.size());
        existingBySymbol = null; // 帮助 GC

        if (symbolMissingRanges.isEmpty()) {
            result.put("success", true);
            result.put("message", "没有发现缺失数据");
            result.put("checkedSymbols", activeSymbols.size());
            result.put("repairedSymbolCount", 0);
            result.put("totalRepairedRecords", 0);
            result.put("timeRange", actualStartTime + " ~ " + actualEndTime);
            return result;
        }

        // 【优化2】使用并行流处理 API 调用（限制并发数避免打满 API 限速）
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(1);
        List<CoinPrice> allRepairedPrices = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        int totalSymbols = symbolMissingRanges.size();

        List<java.util.concurrent.CompletableFuture<Map<String, Object>>> futures = symbolMissingRanges.entrySet()
                .stream()
                .map(entry -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    String symbol = entry.getKey();
                    List<long[]> ranges = entry.getValue();
                    Map<String, Object> symbolResult = new HashMap<>();
                    symbolResult.put("symbol", symbol);

                    int repairedCount = 0;
                    List<String> repairedRangesStr = new ArrayList<>();

                    for (long[] range : ranges) {
                        try {
                            long startMs = range[0];
                            long endMs = range[1];
                            if (endMs <= startMs) {
                                endMs = startMs + 5 * 60 * 1000;
                            }

                            List<KlineData> klines = binanceApiService.getKlinesWithPagination(
                                    symbol, "5m", startMs, endMs, 500);

                            if (!klines.isEmpty()) {
                                List<CoinPrice> coinPrices = klines.stream()
                                        .filter(k -> k.getClosePrice() > 0)
                                        .map(k -> new CoinPrice(k.getSymbol(), k.getTimestamp(),
                                                k.getOpenPrice(), k.getHighPrice(), k.getLowPrice(), k.getClosePrice()))
                                        .collect(Collectors.toList());

                                if (!coinPrices.isEmpty()) {
                                    allRepairedPrices.addAll(coinPrices);
                                    repairedCount += coinPrices.size();

                                    LocalDateTime rangeStart = LocalDateTime.ofInstant(
                                            java.time.Instant.ofEpochMilli(range[0]), ZoneId.of("UTC"));
                                    LocalDateTime rangeEnd = LocalDateTime.ofInstant(
                                            java.time.Instant.ofEpochMilli(range[1]), ZoneId.of("UTC"));
                                    repairedRangesStr.add(rangeStart + " ~ " + rangeEnd);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("修复 {} 数据失败: {}", symbol, e.getMessage());
                        }
                    }

                    int processed = processedCount.incrementAndGet();
                    if (processed % 50 == 0 || processed == totalSymbols) {
                        log.info("已处理 {}/{} 个币种...", processed, totalSymbols);
                    }

                    if (repairedCount > 0) {
                        symbolResult.put("repairedCount", repairedCount);
                        symbolResult.put("repairedRanges", repairedRangesStr);
                        return symbolResult;
                    }
                    return null;
                }, executor))
                .collect(Collectors.toList());

        // 等待所有任务完成
        List<Map<String, Object>> results = futures.stream()
                .map(java.util.concurrent.CompletableFuture::join)
                .filter(r -> r != null)
                .collect(Collectors.toList());

        executor.shutdown();

        // 【优化3】一次性批量插入所有修复的数据
        int totalRepairedRecords = 0;
        if (!allRepairedPrices.isEmpty()) {
            log.info("开始批量插入 {} 条修复数据...", allRepairedPrices.size());
            long insertStart = System.currentTimeMillis();
            jdbcCoinPriceRepository.batchInsert(allRepairedPrices);
            totalRepairedRecords = allRepairedPrices.size();
            log.info("批量插入完成，耗时 {}ms", System.currentTimeMillis() - insertStart);
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;
        log.info("历史数据修复完成，共修复 {} 个币种，{} 条数据，总耗时 {}ms", results.size(), totalRepairedRecords, totalTime);

        result.put("success", true);
        result.put("checkedSymbols", activeSymbols.size());
        result.put("repairedSymbolCount", results.size());
        result.put("totalRepairedRecords", totalRepairedRecords);
        result.put("timeRange", actualStartTime + " ~ " + actualEndTime);
        result.put("totalTimeMs", totalTime);
        result.put("repairedDetails", results);

        return result;
    }

    /**
     * 找出连续的缺失时间段，合并为时间范围
     */
    private List<long[]> findMissingRanges(List<LocalDateTime> missingTimestamps) {
        List<long[]> ranges = new ArrayList<>();
        if (missingTimestamps.isEmpty()) {
            return ranges;
        }

        // 排序
        missingTimestamps.sort(LocalDateTime::compareTo);

        LocalDateTime rangeStart = missingTimestamps.get(0);
        LocalDateTime rangeEnd = rangeStart;

        for (int i = 1; i < missingTimestamps.size(); i++) {
            LocalDateTime current = missingTimestamps.get(i);

            // 如果当前时间点与上一个相差超过5分钟，说明是新的时间段
            if (java.time.temporal.ChronoUnit.MINUTES.between(rangeEnd, current) > 5) {
                // 保存当前时间段
                ranges.add(new long[] {
                        rangeStart.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(),
                        rangeEnd.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
                });
                rangeStart = current;
            }
            rangeEnd = current;
        }

        // 保存最后一个时间段
        ranges.add(new long[] {
                rangeStart.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(),
                rangeEnd.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        });

        return ranges;
    }

    /**
     * 时间对齐到5分钟
     */
    private LocalDateTime alignToFiveMinutes(LocalDateTime time) {
        int minute = time.getMinute();
        int alignedMinute = (minute / 5) * 5;
        return time.withMinute(alignedMinute).withSecond(0).withNano(0);
    }

    /**
     * 时间对齐到5分钟（向上取整）
     */
    private LocalDateTime alignToFiveMinutesCeil(LocalDateTime time) {
        int minute = time.getMinute();
        int second = time.getSecond();
        int nano = time.getNano();
        // 如果已经是整5分钟，直接返回
        if (minute % 5 == 0 && second == 0 && nano == 0) {
            return time.withSecond(0).withNano(0);
        }
        int alignedMinute = ((minute / 5) + 1) * 5;
        if (alignedMinute >= 60) {
            return time.plusHours(1).withMinute(0).withSecond(0).withNano(0);
        }
        return time.withMinute(alignedMinute).withSecond(0).withNano(0);
    }

    /**
     * 获取涨幅分布数据（从数据库读取，速度快）
     *
     * @param hours 基准时间（多少小时前），支持小数
     * @return 涨幅分布数据
     */
    public DistributionData getDistribution(double hours) {
        log.info("计算涨幅分布，基准时间: {}小时前", hours);

        long now = System.currentTimeMillis();
        // 转换为分钟以支持小数小时
        long minutes = (long) (hours * 60);

        // 从数据库获取最新价格
        List<CoinPrice> latestPrices = coinPriceRepository.findLatestPrices();
        if (latestPrices.isEmpty()) {
            log.warn("数据库中没有价格数据，可能需要等待回补完成");
            return null;
        }

        // 获取最新数据时间（用于获取当前价格和计算最高/最低价区间）
        LocalDateTime latestTime = latestPrices.get(0).getTimestamp();

        // 基于当前系统时间（对齐到5分钟边界）计算基准时间
        // 例如：系统时间11:42 → 对齐到11:40 → 减15分钟 → 基准时间11:25
        LocalDateTime alignedNow = alignToFiveMinutes(LocalDateTime.now());
        LocalDateTime baseTime = alignedNow.minusMinutes(minutes);

        // 从数据库获取基准时间的价格
        List<CoinPrice> basePriceList = coinPriceRepository.findEarliestPricesAfter(baseTime);
        if (basePriceList.isEmpty()) {
            log.warn("找不到基准时间 {} 的价格数据", baseTime);
            return null;
        }

        // 调试：打印关键时间点
        LocalDateTime actualBaseTime = basePriceList.get(0).getTimestamp();

        // 转换为Map便于查找
        // 当前价格使用收盘价
        Map<String, Double> currentPriceMap = latestPrices.stream()
                .collect(Collectors.toMap(CoinPrice::getSymbol, CoinPrice::getPrice, (a, b) -> a));
        // 基准价格使用开盘价（更准确地反映起点）
        Map<String, Double> basePriceMap = basePriceList.stream()
                .filter(cp -> cp.getOpenPrice() != null)
                .collect(Collectors.toMap(CoinPrice::getSymbol, CoinPrice::getOpenPrice, (a, b) -> a));

        // 获取时间区间内的最高/最低价格
        List<Object[]> maxPricesResult = coinPriceRepository.findMaxPricesBySymbolInRange(baseTime, latestTime);
        List<Object[]> minPricesResult = coinPriceRepository.findMinPricesBySymbolInRange(baseTime, latestTime);

        Map<String, Double> maxPriceMap = maxPricesResult.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Double) row[1],
                        (a, b) -> a));
        Map<String, Double> minPriceMap = minPricesResult.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Double) row[1],
                        (a, b) -> a));

        log.info("从数据库获取价格: 当前={} 个, 基准={} 个, 最高={} 个, 最低={} 个",
                currentPriceMap.size(), basePriceMap.size(), maxPriceMap.size(), minPriceMap.size());

        // 计算涨跌幅（当前、最高、最低）
        Map<String, Double> changeMap = new HashMap<>();
        Map<String, Double> maxChangeMap = new HashMap<>();
        Map<String, Double> minChangeMap = new HashMap<>();

        for (Map.Entry<String, Double> entry : currentPriceMap.entrySet()) {
            String symbol = entry.getKey();
            Double currentPrice = entry.getValue();
            Double basePrice = basePriceMap.get(symbol);
            Double maxPrice = maxPriceMap.get(symbol);
            Double minPrice = minPriceMap.get(symbol);

            if (basePrice != null && basePrice > 0 && currentPrice != null && currentPrice > 0) {
                double changePercent = (currentPrice - basePrice) / basePrice * 100;
                changeMap.put(symbol, changePercent);

                // 计算最高涨跌幅
                if (maxPrice != null && maxPrice > 0) {
                    double maxChangePercent = (maxPrice - basePrice) / basePrice * 100;
                    maxChangeMap.put(symbol, maxChangePercent);
                }

                // 计算最低涨跌幅
                if (minPrice != null && minPrice > 0) {
                    double minChangePercent = (minPrice - basePrice) / basePrice * 100;
                    minChangeMap.put(symbol, minChangePercent);
                }

            }
        }

        log.info("涨跌幅计算完成: {} 个币种", changeMap.size());

        if (changeMap.isEmpty()) {
            log.warn("没有有效的涨跌幅数据");
            return null;
        }

        // 计算涨跌幅范围
        double minChange = changeMap.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maxChange = changeMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);

        // 根据数据范围动态确定区间大小
        double range = maxChange - minChange;
        double bucketSize;
        if (range <= 2) {
            bucketSize = 0.2; // 0.2%区间，适合分钟级
        } else if (range <= 5) {
            bucketSize = 0.5; // 0.5%区间
        } else if (range <= 20) {
            bucketSize = 1; // 1%区间
        } else if (range <= 50) {
            bucketSize = 2; // 2%区间
        } else {
            bucketSize = 5; // 5%区间，适合长期
        }

        // 计算区间边界
        double bucketMin = Math.floor(minChange / bucketSize) * bucketSize;
        double bucketMax = Math.ceil(maxChange / bucketSize) * bucketSize;

        // 创建区间
        Map<String, List<String>> buckets = new LinkedHashMap<>();
        for (double start = bucketMin; start < bucketMax; start += bucketSize) {
            String rangeKey;
            if (bucketSize < 1) {
                rangeKey = String.format("%.1f%%~%.1f%%", start, start + bucketSize);
            } else {
                rangeKey = String.format("%.0f%%~%.0f%%", start, start + bucketSize);
            }
            buckets.put(rangeKey, new ArrayList<>());
        }

        // 分配币种到区间
        int upCount = 0;
        int downCount = 0;

        for (Map.Entry<String, Double> entry : changeMap.entrySet()) {
            String symbol = entry.getKey();
            double change = entry.getValue();

            if (change > 0)
                upCount++;
            else if (change < 0)
                downCount++;

            // 计算所属区间
            double bucketStart = Math.floor(change / bucketSize) * bucketSize;
            String rangeKey;
            if (bucketSize < 1) {
                rangeKey = String.format("%.1f%%~%.1f%%", bucketStart, bucketStart + bucketSize);
            } else {
                rangeKey = String.format("%.0f%%~%.0f%%", bucketStart, bucketStart + bucketSize);
            }

            if (buckets.containsKey(rangeKey)) {
                buckets.get(rangeKey).add(symbol);
            }
        }

        // 构建响应
        DistributionData data = new DistributionData();
        data.setTimestamp(now);
        data.setTotalCoins(changeMap.size());
        data.setUpCount(upCount);
        data.setDownCount(downCount);

        List<DistributionBucket> distribution = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : buckets.entrySet()) {
            List<String> coins = entry.getValue();

            // 构建带涨跌幅的详情列表，并按涨跌幅排序
            List<DistributionBucket.CoinDetail> coinDetails = coins.stream()
                    .map(symbol -> new DistributionBucket.CoinDetail(
                            symbol,
                            changeMap.getOrDefault(symbol, 0.0),
                            maxChangeMap.getOrDefault(symbol, 0.0),
                            minChangeMap.getOrDefault(symbol, 0.0)))
                    .sorted((a, b) -> Double.compare(b.getChangePercent(), a.getChangePercent())) // 按涨跌幅降序
                    .collect(Collectors.toList());

            distribution.add(new DistributionBucket(entry.getKey(), coins.size(), coins, coinDetails));
        }
        data.setDistribution(distribution);

        // 构建所有币种排行榜（按涨跌幅降序）
        List<DistributionBucket.CoinDetail> allCoinsRanking = changeMap.entrySet().stream()
                .map(e -> new DistributionBucket.CoinDetail(
                        e.getKey(),
                        e.getValue(),
                        maxChangeMap.getOrDefault(e.getKey(), 0.0),
                        minChangeMap.getOrDefault(e.getKey(), 0.0)))
                .sorted((a, b) -> Double.compare(b.getChangePercent(), a.getChangePercent()))
                .collect(Collectors.toList());
        data.setAllCoinsRanking(allCoinsRanking);

        log.info("分布统计完成: 上涨={}, 下跌={}, 区间大小={}%", upCount, downCount, bucketSize);
        return data;
    }

    /**
     * 获取指定时间范围的涨幅分布数据
     *
     * @param startTime 开始时间（基准价格时间点）
     * @param endTime   结束时间（当前价格时间点）
     * @return 涨幅分布数据
     */
    public DistributionData getDistributionByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        // 对齐时间到5分钟边界：统一向下取整
        LocalDateTime alignedStart = alignToFiveMinutes(startTime);
        LocalDateTime alignedEnd = alignToFiveMinutes(endTime);
        log.info("计算涨幅分布，时间范围: {} -> {} (对齐后)", alignedStart, alignedEnd);

        long now = System.currentTimeMillis();

        // 从数据库获取开始时间点（基准）的价格
        List<CoinPrice> basePriceList = coinPriceRepository.findEarliestPricesAfter(alignedStart);
        if (basePriceList.isEmpty()) {
            log.warn("找不到开始时间 {} 附近的价格数据", startTime);
            return null;
        }
        LocalDateTime actualStartTime = basePriceList.get(0).getTimestamp();

        // 从数据库获取结束时间点的价格
        List<CoinPrice> endPriceList = coinPriceRepository.findLatestPricesBefore(alignedEnd);
        if (endPriceList.isEmpty()) {
            log.warn("找不到结束时间 {} 附近的价格数据", alignedEnd);
            return null;
        }
        LocalDateTime actualEndTime = endPriceList.get(0).getTimestamp();

        // 转换为Map便于查找
        // 基准价格使用开盘价
        Map<String, Double> basePriceMap = basePriceList.stream()
                .filter(cp -> cp.getOpenPrice() != null)
                .collect(Collectors.toMap(CoinPrice::getSymbol, CoinPrice::getOpenPrice, (a, b) -> a));
        // 结束价格使用收盘价
        Map<String, Double> endPriceMap = endPriceList.stream()
                .collect(Collectors.toMap(CoinPrice::getSymbol, CoinPrice::getPrice, (a, b) -> a));

        // 获取时间区间内的最高/最低价格
        List<Object[]> maxPricesResult = coinPriceRepository.findMaxPricesBySymbolInRange(actualStartTime,
                actualEndTime);
        List<Object[]> minPricesResult = coinPriceRepository.findMinPricesBySymbolInRange(actualStartTime,
                actualEndTime);

        Map<String, Double> maxPriceMap = maxPricesResult.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Double) row[1],
                        (a, b) -> a));
        Map<String, Double> minPriceMap = minPricesResult.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Double) row[1],
                        (a, b) -> a));

        log.info("从数据库获取价格: 基准={} 个, 结束={} 个, 最高={} 个, 最低={} 个",
                basePriceMap.size(), endPriceMap.size(), maxPriceMap.size(), minPriceMap.size());

        // 计算涨跌幅（使用结束价格与基准价格比较）
        Map<String, Double> changeMap = new HashMap<>();
        Map<String, Double> maxChangeMap = new HashMap<>();
        Map<String, Double> minChangeMap = new HashMap<>();

        for (Map.Entry<String, Double> entry : endPriceMap.entrySet()) {
            String symbol = entry.getKey();
            Double endPrice = entry.getValue();
            Double basePrice = basePriceMap.get(symbol);
            Double maxPrice = maxPriceMap.get(symbol);
            Double minPrice = minPriceMap.get(symbol);

            if (basePrice != null && basePrice > 0 && endPrice != null && endPrice > 0) {
                double changePercent = (endPrice - basePrice) / basePrice * 100;
                changeMap.put(symbol, changePercent);

                // 计算最高涨跌幅
                if (maxPrice != null && maxPrice > 0) {
                    double maxChangePercent = (maxPrice - basePrice) / basePrice * 100;
                    maxChangeMap.put(symbol, maxChangePercent);
                }

                // 计算最低涨跌幅
                if (minPrice != null && minPrice > 0) {
                    double minChangePercent = (minPrice - basePrice) / basePrice * 100;
                    minChangeMap.put(symbol, minChangePercent);
                }
            }
        }

        log.info("涨跌幅计算完成: {} 个币种", changeMap.size());

        if (changeMap.isEmpty()) {
            log.warn("没有有效的涨跌幅数据");
            return null;
        }

        // 计算涨跌幅范围
        double minChange = changeMap.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maxChange = changeMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);

        // 根据数据范围动态确定区间大小
        double range = maxChange - minChange;
        double bucketSize;
        if (range <= 2) {
            bucketSize = 0.2;
        } else if (range <= 5) {
            bucketSize = 0.5;
        } else if (range <= 20) {
            bucketSize = 1;
        } else if (range <= 50) {
            bucketSize = 2;
        } else {
            bucketSize = 5;
        }

        // 计算区间边界
        double bucketMin = Math.floor(minChange / bucketSize) * bucketSize;
        double bucketMax = Math.ceil(maxChange / bucketSize) * bucketSize;

        // 创建区间
        Map<String, List<String>> buckets = new LinkedHashMap<>();
        for (double start = bucketMin; start < bucketMax; start += bucketSize) {
            String rangeKey;
            if (bucketSize < 1) {
                rangeKey = String.format("%.1f%%~%.1f%%", start, start + bucketSize);
            } else {
                rangeKey = String.format("%.0f%%~%.0f%%", start, start + bucketSize);
            }
            buckets.put(rangeKey, new ArrayList<>());
        }

        // 分配币种到区间
        int upCount = 0;
        int downCount = 0;

        for (Map.Entry<String, Double> entry : changeMap.entrySet()) {
            String symbol = entry.getKey();
            double change = entry.getValue();

            if (change > 0)
                upCount++;
            else if (change < 0)
                downCount++;

            double bucketStart = Math.floor(change / bucketSize) * bucketSize;
            String rangeKey;
            if (bucketSize < 1) {
                rangeKey = String.format("%.1f%%~%.1f%%", bucketStart, bucketStart + bucketSize);
            } else {
                rangeKey = String.format("%.0f%%~%.0f%%", bucketStart, bucketStart + bucketSize);
            }

            if (buckets.containsKey(rangeKey)) {
                buckets.get(rangeKey).add(symbol);
            }
        }

        // 构建响应
        DistributionData data = new DistributionData();
        data.setTimestamp(now);
        data.setTotalCoins(changeMap.size());
        data.setUpCount(upCount);
        data.setDownCount(downCount);

        List<DistributionBucket> distribution = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : buckets.entrySet()) {
            List<String> coins = entry.getValue();

            List<DistributionBucket.CoinDetail> coinDetails = coins.stream()
                    .map(symbol -> new DistributionBucket.CoinDetail(
                            symbol,
                            changeMap.getOrDefault(symbol, 0.0),
                            maxChangeMap.getOrDefault(symbol, 0.0),
                            minChangeMap.getOrDefault(symbol, 0.0)))
                    .sorted((a, b) -> Double.compare(b.getChangePercent(), a.getChangePercent()))
                    .collect(Collectors.toList());

            distribution.add(new DistributionBucket(entry.getKey(), coins.size(), coins, coinDetails));
        }
        data.setDistribution(distribution);

        // 构建所有币种排行榜
        List<DistributionBucket.CoinDetail> allCoinsRanking = changeMap.entrySet().stream()
                .map(e -> new DistributionBucket.CoinDetail(
                        e.getKey(),
                        e.getValue(),
                        maxChangeMap.getOrDefault(e.getKey(), 0.0),
                        minChangeMap.getOrDefault(e.getKey(), 0.0)))
                .sorted((a, b) -> Double.compare(b.getChangePercent(), a.getChangePercent()))
                .collect(Collectors.toList());
        data.setAllCoinsRanking(allCoinsRanking);

        log.info("分布统计完成: 时间范围 {} -> {}, 上涨={}, 下跌={}, 区间大小={}%",
                actualStartTime, actualEndTime, upCount, downCount, bucketSize);
        return data;
    }

    /**
     * 获取单边上行涨幅分布数据
     *
     * @param hours            时间范围（小时）
     * @param keepRatio        保留比率阈值（如0.75表示回吐25%涨幅时结束）
     * @param noNewHighCandles 连续多少根K线未创新高视为横盘结束
     * @param minUptrend       最小涨幅过滤（百分比，如4表示只返回>=4%的波段）
     * @param priceMode        价格模式：lowHigh=最低/最高价, openClose=开盘/收盘价
     * @return 单边涨幅分布数据
     */
    public UptrendData getUptrendDistribution(double hours, double keepRatio, int noNewHighCandles, double minUptrend,
            String priceMode) {
        log.info("计算单边上行涨幅分布，时间范围: {}小时，保留比率: {}，横盘K线数: {}, 最小涨幅: {}%, 价格模式: {}", hours, keepRatio, noNewHighCandles,
                minUptrend, priceMode);

        long minutes = (long) (hours * 60);
        LocalDateTime alignedNow = alignToFiveMinutes(LocalDateTime.now());
        LocalDateTime startTime = alignedNow.minusMinutes(minutes);
        LocalDateTime endTime = alignedNow;

        return getUptrendDistributionByTimeRange(startTime, endTime, keepRatio, noNewHighCandles, minUptrend,
                priceMode);
    }

    /**
     * 获取指定时间范围的单边上行涨幅分布数据
     *
     * @param startTime        开始时间
     * @param endTime          结束时间
     * @param keepRatio        保留比率阈值（如0.75表示回吐25%涨幅时结束）
     * @param noNewHighCandles 连续多少根K线未创新高视为横盘结束
     * @param minUptrend       最小涨幅过滤（百分比）
     * @param priceMode        价格模式：lowHigh=最低/最高价, openClose=开盘/收盘价
     * @return 单边涨幅分布数据
     */
    public UptrendData getUptrendDistributionByTimeRange(LocalDateTime startTime, LocalDateTime endTime,
            double keepRatio, int noNewHighCandles, double minUptrend, String priceMode) {
        // 对齐时间到5分钟边界：统一向下取整
        LocalDateTime alignedStart = alignToFiveMinutes(startTime);
        LocalDateTime alignedEnd = alignToFiveMinutes(endTime);

        // 生成缓存 key（包含对齐后的时间和所有参数）
        String cacheKey = String.format("%s_%s_%.2f_%d_%.2f_%s",
                alignedStart.toString(), alignedEnd.toString(), keepRatio, noNewHighCandles, minUptrend, priceMode);

        // 检查缓存
        UptrendData cachedData = uptrendCache.getIfPresent(cacheKey);
        if (cachedData != null) {
            log.info("命中缓存: {}", cacheKey);
            return cachedData;
        }

        log.info("[UPTREND-START] 开始计算单边涨幅分布: {} -> {} (对齐后), 保留比率: {}, 横盘K线数: {}, 最小涨幅: {}%, 价格模式: {}",
                alignedStart, alignedEnd, keepRatio, noNewHighCandles, minUptrend, priceMode);
        logPoolStatus("开始计算前");

        // 【优化】分批查询，避免一次性加载过多数据到内存
        long queryStart = System.currentTimeMillis();

        // Step 1: 先获取时间范围内所有币种列表（只返回字符串，内存占用小）
        List<String> allSymbols = coinPriceRepository.findDistinctSymbolsInRange(alignedStart, alignedEnd);
        if (allSymbols.isEmpty()) {
            log.warn("时间范围内没有数据");
            return null;
        }

        log.info("找到 {} 个币种，开始分币种并行处理...", allSymbols.size());

        // Step 2: 并行处理（每个币种独立查询并计算）
        List<UptrendData.CoinUptrend> allWaves;
        try {
            logPoolStatus("提交任务前");
            long calcStart = System.currentTimeMillis();

            // 提交任务并设置超时时间（120秒）
            allWaves = waveCalculationPool.submit(() -> allSymbols.parallelStream()
                    .flatMap(symbol -> {
                        // 在并行线程中单独查询该币种的数据，使用 DTO 投影大幅减少内存消耗
                        List<CoinPriceDTO> symbolPrices = coinPriceRepository.findDTOBySymbolInRangeOrderByTime(symbol,
                                alignedStart, alignedEnd);
                        return calculateSymbolAllWavesFromData(symbol, symbolPrices, keepRatio,
                                noNewHighCandles, minUptrend, priceMode).stream();
                    })
                    .collect(java.util.stream.Collectors.toList()))
                    .get(120, TimeUnit.SECONDS);

            long calcTime = System.currentTimeMillis() - calcStart;
            log.info("[UPTREND-CALC] 并行计算完成，共耗时 {}ms", calcTime);
            logPoolStatus("计算完成后");
        } catch (TimeoutException e) {
            log.error("[UPTREND-TIMEOUT] 计算超时（>120s），线程池状态：活跃={}, 队列={}",
                    waveCalculationPool.getActiveThreadCount(), waveCalculationPool.getQueuedTaskCount());
            logPoolStatus("超时发生时");
            return null;
        } catch (Exception e) {
            log.error("[UPTREND-ERROR] 波段计算失败，堆内存使用: {}MB",
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024, e);
            return null;
        }

        long queryTime = System.currentTimeMillis() - queryStart;
        log.info("[UPTREND-DONE] 一次性查询处理完成，耗时 {}ms，共 {} 个波段，堆内存使用: {}MB",
                queryTime, allWaves.size(),
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024);

        // 统计进行中的波段数
        int ongoingCount = (int) allWaves.stream().filter(UptrendData.CoinUptrend::isOngoing).count();

        if (allWaves.isEmpty()) {
            log.warn("没有有效的单边涨幅数据");
            return null;
        }

        // 按单边涨幅降序排序
        allWaves.sort((a, b) -> Double.compare(b.getUptrendPercent(), a.getUptrendPercent()));

        // 计算统计信息
        double totalUptrend = allWaves.stream().mapToDouble(UptrendData.CoinUptrend::getUptrendPercent).sum();
        double avgUptrend = totalUptrend / allWaves.size();
        double maxUptrendValue = allWaves.get(0).getUptrendPercent();
        double minUptrendValue = allWaves.stream().mapToDouble(UptrendData.CoinUptrend::getUptrendPercent).min()
                .orElse(0);

        // 根据数据范围动态确定区间大小（与涨幅分布一致）
        double range = maxUptrendValue - minUptrendValue;
        double bucketSize;
        if (range <= 2) {
            bucketSize = 0.2; // 0.2%区间，适合极小范围
        } else if (range <= 5) {
            bucketSize = 0.5; // 0.5%区间
        } else if (range <= 20) {
            bucketSize = 1; // 1%区间
        } else if (range <= 50) {
            bucketSize = 2; // 2%区间
        } else {
            bucketSize = 5; // 5%区间，适合大范围
        }

        // 计算区间边界
        double bucketMin = Math.floor(minUptrendValue / bucketSize) * bucketSize;
        double bucketMax = Math.ceil(maxUptrendValue / bucketSize) * bucketSize;

        // 创建区间
        Map<String, List<UptrendData.CoinUptrend>> bucketMap = new LinkedHashMap<>();
        for (double start = bucketMin; start < bucketMax; start += bucketSize) {
            String rangeKey;
            if (bucketSize < 1) {
                rangeKey = String.format("%.1f%%~%.1f%%", start, start + bucketSize);
            } else {
                rangeKey = String.format("%.0f%%~%.0f%%", start, start + bucketSize);
            }
            bucketMap.put(rangeKey, new ArrayList<>());
        }

        // 分配波段到区间
        for (UptrendData.CoinUptrend wave : allWaves) {
            double pct = wave.getUptrendPercent();
            double bucketStart = Math.floor(pct / bucketSize) * bucketSize;
            String rangeKey;
            if (bucketSize < 1) {
                rangeKey = String.format("%.1f%%~%.1f%%", bucketStart, bucketStart + bucketSize);
            } else {
                rangeKey = String.format("%.0f%%~%.0f%%", bucketStart, bucketStart + bucketSize);
            }
            if (bucketMap.containsKey(rangeKey)) {
                bucketMap.get(rangeKey).add(wave);
            }
        }

        List<UptrendData.UptrendBucket> distribution = new ArrayList<>();
        for (Map.Entry<String, List<UptrendData.CoinUptrend>> entry : bucketMap.entrySet()) {
            List<UptrendData.CoinUptrend> waves = entry.getValue();
            int bucketOngoing = (int) waves.stream().filter(UptrendData.CoinUptrend::isOngoing).count();
            distribution.add(new UptrendData.UptrendBucket(entry.getKey(), waves.size(), bucketOngoing, waves));
        }

        // 构建响应
        UptrendData data = new UptrendData();
        data.setTimestamp(System.currentTimeMillis());
        data.setTotalCoins(allWaves.size()); // 现在是波段总数
        data.setPullbackThreshold(keepRatio);
        data.setAvgUptrend(Math.round(avgUptrend * 100) / 100.0);
        data.setMaxUptrend(Math.round(maxUptrendValue * 100) / 100.0);
        data.setOngoingCount(ongoingCount);
        data.setDistribution(distribution);
        data.setAllCoinsRanking(allWaves);

        log.info("单边涨幅分布计算完成: 总波段数={}, 进行中={}, 平均涨幅={}%, 最大涨幅={}%",
                allWaves.size(), ongoingCount, data.getAvgUptrend(), data.getMaxUptrend());

        // 存入缓存
        uptrendCache.put(cacheKey, data);
        log.debug("结果已缓存: {}", cacheKey);

        return data;
    }

    /**
     * 计算并返回某个币种的所有符合条件的单边涨幅波段（直接计算，不检查缓存）
     */
    private List<UptrendData.CoinUptrend> calculateSymbolAllWaves(String symbol, LocalDateTime startTime,
            LocalDateTime endTime, double keepRatio, int noNewHighCandles, double minUptrend) {
        // 使用 DTO 版本
        List<CoinPriceDTO> prices = coinPriceRepository.findDTOBySymbolInRangeOrderByTime(symbol, startTime, endTime);
        if (prices == null || prices.size() < 2) {
            return Collections.emptyList();
        }

        return calculateSymbolAllWavesFromData(symbol, prices, keepRatio, noNewHighCandles, minUptrend, "lowHigh");
    }

    /**
     * 计算单个币种的所有符合条件的单边涨幅波段（使用已加载的数据）
     *
     * 【深度优化版本】
     * 1. 修复了旧版 O(N^2) 的 indexOf 性能陷阱
     * 2. 使用索引遍历代替 Iterator
     * 3. 使用 UTC_ZONE 常量减少对象创建
     *
     * @param symbol           币种
     * @param prices           该币种的价格DTo数据列表
     * @param keepRatio        保留比率阈值
     * @param noNewHighCandles 连续多少根K线未创新高视为横盘结束
     * @param minUptrend       最小涨幅过滤
     * @param priceMode        价格模式
     * @return 该币种的所有符合条件的单边涨幅波段列表
     */
    private List<UptrendData.CoinUptrend> calculateSymbolAllWavesFromData(String symbol, List<CoinPriceDTO> prices,
            double keepRatio, int noNewHighCandles, double minUptrend, String priceMode) {
        if (prices == null || prices.size() < 2) {
            return Collections.emptyList();
        }

        List<UptrendData.CoinUptrend> waves = new ArrayList<>();

        // 波段跟踪变量
        double waveStartPrice = 0;
        double wavePeakPrice = 0;
        double waveLowestLow = 0;
        long waveStartTimeMs = 0;
        long wavePeakTimeMs = 0;
        LocalDateTime waveStartTime = null;
        LocalDateTime wavePeakTime = null;
        int candlesSinceNewHigh = 0;

        // 当前波段状态
        boolean inWave = false;

        // 使用索引进行高效遍历，避免 indexOf (O(N^2))
        for (int i = 0; i < prices.size(); i++) {
            CoinPriceDTO price = prices.get(i);
            double openPrice = price.getOpenPrice() != null ? price.getOpenPrice() : price.getPrice();
            double highPrice = price.getHighPrice() != null ? price.getHighPrice() : price.getPrice();
            double lowPrice = price.getLowPrice() != null ? price.getLowPrice() : price.getPrice();
            double closePrice = price.getPrice();
            LocalDateTime timestamp = price.getTimestamp();

            double startPriceCandidate;
            double peakPriceCandidate;
            if ("openClose".equals(priceMode)) {
                startPriceCandidate = openPrice;
                peakPriceCandidate = closePrice;
            } else if ("openHigh".equals(priceMode)) {
                startPriceCandidate = openPrice;
                peakPriceCandidate = highPrice;
            } else { // lowHigh (默认)
                startPriceCandidate = lowPrice;
                peakPriceCandidate = highPrice;
            }

            if (!inWave) {
                waveStartPrice = startPriceCandidate;
                waveStartTime = timestamp;
                waveStartTimeMs = timestamp.atZone(UTC_ZONE).toInstant().toEpochMilli();
                waveLowestLow = "lowHigh".equals(priceMode) ? lowPrice : openPrice;
                wavePeakPrice = peakPriceCandidate;
                wavePeakTime = timestamp;
                wavePeakTimeMs = waveStartTimeMs;
                candlesSinceNewHigh = 0;
                inWave = true;
            } else {
                boolean madeNewHigh = false;
                if (peakPriceCandidate > wavePeakPrice) {
                    wavePeakPrice = peakPriceCandidate;
                    wavePeakTime = timestamp;
                    wavePeakTimeMs = timestamp.atZone(UTC_ZONE).toInstant().toEpochMilli();
                    candlesSinceNewHigh = 0;
                    madeNewHigh = true;
                } else {
                    candlesSinceNewHigh++;
                }

                double breakdownPrice = "lowHigh".equals(priceMode) ? lowPrice : openPrice;
                if (breakdownPrice < waveLowestLow) {
                    waveStartPrice = startPriceCandidate;
                    waveStartTime = timestamp;
                    waveStartTimeMs = timestamp.atZone(UTC_ZONE).toInstant().toEpochMilli();
                    waveLowestLow = breakdownPrice;
                    wavePeakPrice = peakPriceCandidate;
                    wavePeakTime = timestamp;
                    wavePeakTimeMs = waveStartTimeMs;
                    candlesSinceNewHigh = 0;
                    continue;
                }

                double range = wavePeakPrice - waveStartPrice;
                double positionRatio = range > 0 ? (closePrice - waveStartPrice) / range : 1.0;

                boolean positionTrigger = !madeNewHigh && positionRatio < keepRatio && range > 0;
                boolean sidewaysTrigger = noNewHighCandles > 0 && candlesSinceNewHigh >= noNewHighCandles;

                if (positionTrigger || sidewaysTrigger) {
                    double uptrendPercent = waveStartPrice > 0 ? (wavePeakPrice - waveStartPrice) / waveStartPrice * 100
                            : 0;
                    boolean isDifferentCandle = waveStartTimeMs != wavePeakTimeMs;

                    if (uptrendPercent >= minUptrend && isDifferentCandle) {
                        waves.add(new UptrendData.CoinUptrend(
                                symbol, Math.round(uptrendPercent * 100) / 100.0, false,
                                waveStartTimeMs, wavePeakTimeMs, waveStartPrice, wavePeakPrice));
                    }

                    // 【极其重要：算法优化】不再使用 indexOf，直接基于当前索引回退
                    double lowestPrice = startPriceCandidate;
                    LocalDateTime lowestTime = timestamp;

                    // 从当前位置往回拉，直到波段顶点，找到真正的最低点作为下个波段起点
                    for (int j = i; j >= 0; j--) {
                        CoinPriceDTO p = prices.get(j);
                        if (p.getTimestamp().isBefore(wavePeakTime))
                            break;

                        double pStart = "lowHigh".equals(priceMode)
                                ? (p.getLowPrice() != null ? p.getLowPrice() : p.getPrice())
                                : (p.getOpenPrice() != null ? p.getOpenPrice() : p.getPrice());

                        if (pStart <= lowestPrice) {
                            lowestPrice = pStart;
                            lowestTime = p.getTimestamp();
                        }
                    }

                    waveStartPrice = lowestPrice;
                    waveStartTime = lowestTime;
                    waveStartTimeMs = lowestTime.atZone(UTC_ZONE).toInstant().toEpochMilli();
                    waveLowestLow = lowestPrice;
                    wavePeakPrice = peakPriceCandidate;
                    wavePeakTime = timestamp;
                    wavePeakTimeMs = timestamp.atZone(UTC_ZONE).toInstant().toEpochMilli();
                    candlesSinceNewHigh = 0;
                }
            }
        }

        // 处理进行中的最后一个波段
        if (inWave && waveStartPrice > 0 && wavePeakPrice > waveStartPrice) {
            double uptrendPercent = (wavePeakPrice - waveStartPrice) / waveStartPrice * 100;
            boolean isDifferentCandle = waveStartTimeMs != wavePeakTimeMs;
            boolean stillValid = noNewHighCandles == -1 || candlesSinceNewHigh < noNewHighCandles;

            if (uptrendPercent >= minUptrend && isDifferentCandle) {
                waves.add(new UptrendData.CoinUptrend(
                        symbol, Math.round(uptrendPercent * 100) / 100.0, stillValid,
                        waveStartTimeMs, wavePeakTimeMs, waveStartPrice, wavePeakPrice));
            }
        }
        return waves;
    }

    // ==================== V2 优化版本：两阶段并发回补 ====================

    /**
     * 优化版历史数据回补（V2）
     *
     * 主要优化：
     * 1. 两阶段回补：第一阶段用固定结束时间，第二阶段补充期间新增数据
     * 2. 并发获取：使用 Semaphore 控制并发数
     * 3. 立即保存：每个币种获取后立即保存到数据库（DB 耗时作为自然间隔）
     * 4. limit=99：降低 API 权重消耗（权重 1 vs 原来的 5）
     * 5. 每阶段完成后立即计算指数
     *
     * @param days        回补天数
     * @param concurrency 并发数（建议 5-10）
     */
    public void backfillHistoricalDataV2(int days, int concurrency) {
        log.info("========== 开始 V2 优化版回补（{}天，并发数{}）==========", days, concurrency);
        long totalStartTime = System.currentTimeMillis();

        // 1. 从数据库加载基准价格
        List<BasePrice> existingBasePrices = basePriceRepository.findAll();
        if (!existingBasePrices.isEmpty()) {
            basePrices = existingBasePrices.stream()
                    .collect(Collectors.toMap(BasePrice::getSymbol, BasePrice::getPrice, (a, b) -> a));
            basePriceTime = existingBasePrices.get(0).getCreatedAt();
            log.info("从数据库加载基准价格成功，共 {} 个币种", basePrices.size());
        } else {
            log.info("数据库中没有基准价格，将从历史数据初始化");
        }

        // 2. 查询数据库最晚时间点（用于增量回补判断）
        LocalDateTime dbLatest = coinPriceRepository.findLatestTimestamp();

        // 3. 计算第一阶段的时间范围（固定结束时间，所有币种共用）
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        LocalDateTime phase1EndTime = alignToFiveMinutes(now).minusMinutes(5); // 最新闭合K线
        LocalDateTime phase1StartTime;

        if (dbLatest == null) {
            // 数据库为空，回补 N 天
            phase1StartTime = phase1EndTime.minusDays(days);
            log.info("数据库为空，全量回补 {} 天", days);
        } else if (!dbLatest.isBefore(phase1EndTime)) {
            // 数据库已是最新，跳过回补
            log.info("数据库已是最新（dbLatest={}, latestClosed={}），跳过API回补", dbLatest, phase1EndTime);
            return;
        } else {
            // 增量回补：从 dbLatest + 5min 开始
            phase1StartTime = dbLatest.plusMinutes(5);
            log.info("增量回补模式：从 {} 到 {} (dbLatest={})", phase1StartTime, phase1EndTime, dbLatest);
        }

        // ==================== 第一阶段：主回补 ====================
        log.info("========== 第一阶段：主回补 ==========");
        log.info("时间范围: {} -> {} (固定)", phase1StartTime, phase1EndTime);

        Map<String, Double> phase1BasePrices = backfillPhaseV2(phase1StartTime, phase1EndTime, concurrency,
                existingBasePrices.isEmpty());

        // 更新基准价格（如果是首次运行）
        if (existingBasePrices.isEmpty() && !phase1BasePrices.isEmpty()) {
            basePrices = new HashMap<>(phase1BasePrices);
            basePriceTime = LocalDateTime.now();
            List<BasePrice> basePriceList = phase1BasePrices.entrySet().stream()
                    .map(e -> new BasePrice(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            basePriceRepository.saveAll(basePriceList);
            log.info("基准价格已初始化并保存，共 {} 个币种", basePriceList.size());
        } else if (!existingBasePrices.isEmpty() && !phase1BasePrices.isEmpty()) {
            // 检查是否有新币需要添加
            Set<String> newSymbols = new HashSet<>(phase1BasePrices.keySet());
            newSymbols.removeAll(basePrices.keySet());
            if (!newSymbols.isEmpty()) {
                for (String symbol : newSymbols) {
                    basePrices.put(symbol, phase1BasePrices.get(symbol));
                }
                List<BasePrice> newBasePriceList = newSymbols.stream()
                        .map(s -> new BasePrice(s, phase1BasePrices.get(s)))
                        .collect(Collectors.toList());
                basePriceRepository.saveAll(newBasePriceList);
                log.info("新币基准价格已保存: {} 个", newSymbols.size());
            }
        }

        // 第一阶段：计算指数
        log.info("第一阶段：计算指数...");
        calculateAndSaveIndexesForRange(phase1StartTime, phase1EndTime);

        // ==================== 第二阶段：增量回补 ====================
        log.info("========== 第二阶段：增量回补 ==========");

        LocalDateTime phase2StartTime = phase1EndTime.plusMinutes(5);
        LocalDateTime phase2EndTime = alignToFiveMinutes(LocalDateTime.now(java.time.ZoneOffset.UTC)).minusMinutes(5);

        if (!phase2StartTime.isAfter(phase2EndTime)) {
            log.info("增量范围: {} -> {}", phase2StartTime, phase2EndTime);
            backfillPhaseV2(phase2StartTime, phase2EndTime, concurrency, false);

            // 第二阶段：计算指数
            log.info("第二阶段：计算指数...");
            calculateAndSaveIndexesForRange(phase2StartTime, phase2EndTime);
        } else {
            log.info("无需增量回补，数据已是最新");
        }

        long totalElapsed = System.currentTimeMillis() - totalStartTime;
        log.info("========== V2 回补全部完成！总耗时: {}ms ({}分钟) ==========",
                totalElapsed, totalElapsed / 60000);
    }

    /**
     * 执行单个阶段的并发回补（方案B：每次 API 调用后立即保存）
     *
     * 关键改进：不使用 getKlinesWithPagination，而是自己控制每次 API 调用后立即保存
     * 这样 DB 操作的耗时成为自然的 API 间隔，避免被限流
     *
     * @param startTime         开始时间
     * @param endTime           结束时间
     * @param concurrency       并发数
     * @param collectBasePrices 是否收集基准价格（首次运行时需要）
     * @return 收集到的基准价格（每个币种最早的 openPrice）
     */
    private Map<String, Double> backfillPhaseV2(LocalDateTime startTime, LocalDateTime endTime,
            int concurrency, boolean collectBasePrices) {

        List<String> symbols = binanceApiService.getAllUsdtSymbols();
        if (symbols.isEmpty()) {
            log.warn("无法获取交易对列表");
            return Collections.emptyMap();
        }

        long startMs = startTime.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();
        long endMs = endTime.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();

        // 批量查询已存在的时间戳（优化）
        Set<LocalDateTime> existingTimestamps = new HashSet<>(
                coinPriceRepository.findAllDistinctTimestampsBetween(startTime, endTime));
        log.info("本阶段已存在 {} 个时间点将跳过", existingTimestamps.size());

        Semaphore semaphore = new Semaphore(concurrency);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger totalApiCalls = new AtomicInteger(0);
        AtomicInteger totalSaved = new AtomicInteger(0);

        // 用于收集新币的基准价格（线程安全）
        Map<String, Double> collectedBasePrices = new java.util.concurrent.ConcurrentHashMap<>();

        long phaseStartTime = System.currentTimeMillis();

        List<CompletableFuture<Void>> futures = symbols.stream()
                .map(symbol -> CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();

                        // 分批获取并立即保存（方案B核心逻辑）
                        long currentStart = startMs;
                        boolean isFirstBatch = true;

                        while (currentStart <= endMs) {
                            // 检查是否被限流
                            if (binanceApiService.isRateLimited()) {
                                log.warn("检测到限流，停止回补 {}", symbol);
                                break;
                            }

                            // 获取一批 K 线（limit=500，权重5）
                            List<KlineData> batch = binanceApiService.getKlines(
                                    symbol, "5m", currentStart, endMs, 500);

                            totalApiCalls.incrementAndGet();

                            if (batch.isEmpty()) {
                                break;
                            }

                            // 收集基准价格（使用第一批的第一条）
                            if (collectBasePrices && isFirstBatch && !batch.isEmpty()) {
                                collectedBasePrices.putIfAbsent(symbol, batch.get(0).getOpenPrice());
                                isFirstBatch = false;
                            }

                            // 立即过滤并保存这一批（方案B：每次API调用后立即保存）
                            List<CoinPrice> pricesToSave = new ArrayList<>();
                            for (KlineData kline : batch) {
                                LocalDateTime timestamp = kline.getTimestamp();
                                if (!existingTimestamps.contains(timestamp) && kline.getClosePrice() > 0) {
                                    pricesToSave.add(new CoinPrice(
                                            kline.getSymbol(), timestamp,
                                            kline.getOpenPrice(), kline.getHighPrice(),
                                            kline.getLowPrice(), kline.getClosePrice(),
                                            kline.getVolume())); // 成交额
                                }
                            }

                            // 立即保存
                            if (!pricesToSave.isEmpty()) {
                                jdbcCoinPriceRepository.batchInsert(pricesToSave);
                                totalSaved.addAndGet(pricesToSave.size());
                            }

                            // 使用配置的请求间隔，确保不超过速率限制
                            long intervalMs = binanceApiService.getRequestIntervalMs();
                            if (intervalMs > 0) {
                                try {
                                    Thread.sleep(intervalMs);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }

                            // 计算下一批的起始时间
                            KlineData lastKline = batch.get(batch.size() - 1);
                            long lastTime = lastKline.getTimestamp()
                                    .atZone(ZoneId.of("UTC"))
                                    .toInstant()
                                    .toEpochMilli();
                            currentStart = lastTime + 300000; // +5分钟
                        }

                        int done = completed.incrementAndGet();
                        if (done % 50 == 0 || done == symbols.size()) {
                            long elapsed = System.currentTimeMillis() - phaseStartTime;
                            log.info("回补进度: {}/{} (API调用:{}, 已保存:{}条) 耗时:{}s",
                                    done, symbols.size(), totalApiCalls.get(), totalSaved.get(), elapsed / 1000);
                        }

                    } catch (Exception e) {
                        int failCount = failed.incrementAndGet();
                        log.error("回补失败 {}: {}", symbol, e.getMessage());

                        // 连续失败多次时等待
                        if (failCount % 10 == 0) {
                            log.warn("连续失败 {} 次，等待5秒后继续...", failCount);
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } finally {
                        semaphore.release();
                    }
                }, executorService))
                .collect(Collectors.toList());

        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long phaseElapsed = System.currentTimeMillis() - phaseStartTime;
        log.info("本阶段完成: 成功={}, 跳过={}, 失败={}, API调用={}, 保存={}条, 耗时={}s",
                completed.get(), skipped.get(), failed.get(), totalApiCalls.get(), totalSaved.get(),
                phaseElapsed / 1000);

        return collectedBasePrices;
    }

    /**
     * 计算并保存指定时间范围内的所有指数
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     */
    private void calculateAndSaveIndexesForRange(LocalDateTime startTime, LocalDateTime endTime) {
        // 批量查询已存在的指数时间戳
        Set<LocalDateTime> existingIndexTimestamps = new HashSet<>(
                marketIndexRepository.findAllTimestampsBetween(startTime, endTime));

        // 获取所有需要计算的时间点
        List<LocalDateTime> timestamps = coinPriceRepository.findAllDistinctTimestampsBetween(startTime, endTime);
        log.info("时间范围内有 {} 个时间点，已有指数 {} 个", timestamps.size(), existingIndexTimestamps.size());

        List<MarketIndex> indexList = new ArrayList<>();

        for (LocalDateTime timestamp : timestamps) {
            // 跳过已存在的
            if (existingIndexTimestamps.contains(timestamp)) {
                continue;
            }

            // 获取该时间点的所有币种价格
            List<CoinPrice> prices = coinPriceRepository.findByTimestamp(timestamp);

            double totalChange = 0;
            double totalVolume = 0;
            int validCount = 0;
            int upCount = 0;
            int downCount = 0;

            for (CoinPrice price : prices) {
                String symbol = price.getSymbol();
                Double basePrice = basePrices.get(symbol);

                if (basePrice == null || basePrice <= 0) {
                    continue;
                }

                double changePercent = (price.getPrice() - basePrice) / basePrice * 100;

                // 累加成交额
                if (price.getVolume() != null) {
                    totalVolume += price.getVolume();
                }

                if (changePercent > 0) {
                    upCount++;
                } else if (changePercent < 0) {
                    downCount++;
                }

                totalChange += changePercent;
                validCount++;
            }

            if (validCount > 0) {
                double indexValue = totalChange / validCount;
                double adr = downCount > 0 ? (double) upCount / downCount : upCount;
                indexList.add(new MarketIndex(timestamp, indexValue, totalVolume, validCount, upCount, downCount, adr));
            }
        }

        // 批量保存
        if (!indexList.isEmpty()) {
            marketIndexRepository.saveAll(indexList);
            log.info("指数计算完成，新增 {} 条记录", indexList.size());
        } else {
            log.info("无新指数需要保存");
        }
    }

    /**
     * 做空涨幅榜前N名回测
     * 
     * @param rankingHours 涨幅排行榜时间范围（24/48/72/168小时）
     * @param holdHours    持仓时间（小时）
     * @param topN         做空前N名
     */
    public com.binance.index.dto.BacktestResult runShortTop10Backtest(
            int entryHour, int entryMinute, double amountPerCoin, int days, int rankingHours, int holdHours,
            int topN, String timezone) {

        log.info("开始做空涨幅榜前{}名回测: 入场时间={}:{}, 每币金额={}U, 每日总额={}U, 回测{}天, 涨幅榜{}小时, 持仓{}小时, 时区={}",
                topN, entryHour, entryMinute, amountPerCoin, Math.round(amountPerCoin * topN * 100) / 100.0, days,
                rankingHours, holdHours, timezone);

        java.time.ZoneId userZone = java.time.ZoneId.of(timezone);
        java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");

        java.time.LocalDate today = java.time.LocalDate.now(userZone);
        java.time.LocalDate endDate = today.minusDays(1);
        java.time.LocalDate startDate = endDate.minusDays(days - 1);

        log.info("回测日期范围: {} 至 {}", startDate, endDate);

        List<com.binance.index.dto.BacktestDailyResult> dailyResults = new ArrayList<>();
        List<String> skippedDays = new ArrayList<>();

        int totalTrades = 0;
        int winTrades = 0;
        int loseTrades = 0;
        int winDays = 0; // 盈利天数（当日总盈亏>0）
        int loseDays = 0; // 亏损天数（当日总盈亏<=0）
        double totalProfit = 0;

        for (java.time.LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            java.time.LocalDateTime entryTimeLocal = date.atTime(entryHour, entryMinute);
            // 使用 holdHours 计算平仓时间
            java.time.LocalDateTime exitTimeLocal = entryTimeLocal.plusHours(holdHours);
            // 使用 rankingHours 计算涨幅基准时间
            java.time.LocalDateTime changeBaseTimeLocal = entryTimeLocal.minusHours(rankingHours);

            java.time.LocalDateTime entryTimeUtc = entryTimeLocal.atZone(userZone).withZoneSameInstant(utcZone)
                    .toLocalDateTime();
            java.time.LocalDateTime exitTimeUtc = exitTimeLocal.atZone(userZone).withZoneSameInstant(utcZone)
                    .toLocalDateTime();
            java.time.LocalDateTime changeBaseTimeUtc = changeBaseTimeLocal.atZone(userZone)
                    .withZoneSameInstant(utcZone).toLocalDateTime();

            // 使用 openPrice 作为该时刻的价格：12:00的K线的openPrice就是12:00那一刻的价格
            // 无需时间偏移，逻辑更清晰
            List<CoinPrice> changeBasePrices = findClosestPricesForBacktestCached(changeBaseTimeUtc, 30);
            List<CoinPrice> entryPrices = findClosestPricesForBacktestCached(entryTimeUtc, 30);
            List<CoinPrice> exitPrices = findClosestPricesForBacktestCached(exitTimeUtc, 30);

            if (changeBasePrices.isEmpty() || entryPrices.isEmpty() || exitPrices.isEmpty()) {
                log.warn("日期 {} 数据不完整，跳过", date);
                skippedDays.add(date.toString());
                continue;
            }

            // 使用 openPrice 获取价格（如果没有则回退到 closePrice）
            Map<String, Double> changeBaseMap = changeBasePrices.stream()
                    .collect(Collectors.toMap(CoinPrice::getSymbol,
                            p -> p.getOpenPrice() != null ? p.getOpenPrice() : p.getPrice(), (a, b) -> a));
            Map<String, Double> entryMap = entryPrices.stream()
                    .collect(Collectors.toMap(CoinPrice::getSymbol,
                            p -> p.getOpenPrice() != null ? p.getOpenPrice() : p.getPrice(), (a, b) -> a));
            Map<String, Double> exitMap = exitPrices.stream()
                    .collect(Collectors.toMap(CoinPrice::getSymbol,
                            p -> p.getOpenPrice() != null ? p.getOpenPrice() : p.getPrice(), (a, b) -> a));

            List<Map.Entry<String, Double>> changeList = new ArrayList<>();
            for (Map.Entry<String, Double> entry : entryMap.entrySet()) {
                String symbol = entry.getKey();
                Double entryPrice = entry.getValue();
                Double basePrice = changeBaseMap.get(symbol);
                if (basePrice != null && basePrice > 0 && entryPrice != null && entryPrice > 0) {
                    double changePercent = (entryPrice - basePrice) / basePrice * 100;
                    changeList.add(new java.util.AbstractMap.SimpleEntry<>(symbol, changePercent));
                }
            }

            changeList.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            // 使用 topN 参数选取前N名
            List<Map.Entry<String, Double>> topCoins = changeList.stream().limit(topN).collect(Collectors.toList());

            if (topCoins.isEmpty()) {
                skippedDays.add(date.toString());
                continue;
            }

            List<com.binance.index.dto.BacktestTrade> trades = new ArrayList<>();
            double dailyProfit = 0;
            int dailyWin = 0;
            int dailyLose = 0;

            for (Map.Entry<String, Double> entry : topCoins) {
                String symbol = entry.getKey();
                Double change24h = entry.getValue();
                Double entryPrice = entryMap.get(symbol);
                Double exitPrice = exitMap.get(symbol);

                if (entryPrice == null || exitPrice == null || entryPrice <= 0)
                    continue;

                double profitPercent = (entryPrice - exitPrice) / entryPrice * 100;
                double profit = amountPerCoin * profitPercent / 100;

                trades.add(new com.binance.index.dto.BacktestTrade(
                        symbol, entryPrice, exitPrice, change24h,
                        Math.round(profit * 100) / 100.0,
                        Math.round(profitPercent * 100) / 100.0));

                dailyProfit += profit;
                if (profit > 0) {
                    dailyWin++;
                    winTrades++;
                } else {
                    dailyLose++;
                    loseTrades++;
                }
                totalTrades++;
            }

            totalProfit += dailyProfit;

            // 统计每日胜率
            if (dailyProfit > 0) {
                winDays++;
            } else {
                loseDays++;
            }

            com.binance.index.dto.BacktestDailyResult dailyResult = new com.binance.index.dto.BacktestDailyResult();
            dailyResult.setDate(date.toString());
            dailyResult.setEntryTime(entryTimeLocal.toString());
            dailyResult.setExitTime(exitTimeLocal.toString());
            dailyResult.setTotalProfit(Math.round(dailyProfit * 100) / 100.0);
            dailyResult.setWinCount(dailyWin);
            dailyResult.setLoseCount(dailyLose);
            dailyResult.setTrades(trades);
            dailyResults.add(dailyResult);
        }

        com.binance.index.dto.BacktestResult result = new com.binance.index.dto.BacktestResult();
        result.setTotalDays(days);
        result.setValidDays(dailyResults.size());
        result.setTotalTrades(totalTrades);
        result.setWinTrades(winTrades);
        result.setLoseTrades(loseTrades);
        result.setWinRate(totalTrades > 0 ? Math.round(winTrades * 10000.0 / totalTrades) / 100.0 : 0);
        result.setWinDays(winDays);
        result.setLoseDays(loseDays);
        result.setDailyWinRate(
                dailyResults.size() > 0 ? Math.round(winDays * 10000.0 / dailyResults.size()) / 100.0 : 0);

        // 计算每月胜率（30天为一个月）
        List<BacktestMonthlyResult> monthlyDetail = new ArrayList<>();
        int winMonths = 0;
        int loseMonths = 0;
        double monthProfit = 0;
        int mWinDays = 0;
        int mLoseDays = 0;
        int monthCount = 0;

        for (int i = 0; i < dailyResults.size(); i++) {
            com.binance.index.dto.BacktestDailyResult dr = dailyResults.get(i);
            monthProfit += dr.getTotalProfit();
            if (dr.getTotalProfit() > 0)
                mWinDays++;
            else if (dr.getTotalProfit() < 0)
                mLoseDays++;

            // 每30天结算一次，或者到达最后一天
            if ((i + 1) % 30 == 0 || i == dailyResults.size() - 1) {
                monthCount++;
                BacktestMonthlyResult mr = new BacktestMonthlyResult();
                mr.setMonthLabel("第 " + monthCount + " 个月");
                mr.setTotalProfit(Math.round(monthProfit * 100) / 100.0);
                mr.setWinDays(mWinDays);
                mr.setLoseDays(mLoseDays);
                monthlyDetail.add(mr);

                if (monthProfit > 0) {
                    winMonths++;
                } else {
                    loseMonths++;
                }
                monthProfit = 0; // 重置月度累计
                mWinDays = 0;
                mLoseDays = 0;
            }
        }
        int totalMonths = winMonths + loseMonths;
        result.setWinMonths(winMonths);
        result.setLoseMonths(loseMonths);
        result.setMonthlyWinRate(totalMonths > 0 ? Math.round(winMonths * 10000.0 / totalMonths) / 100.0 : 0);
        result.setMonthlyResults(monthlyDetail);

        result.setTotalProfit(Math.round(totalProfit * 100) / 100.0);
        result.setDailyResults(dailyResults);
        result.setSkippedDays(skippedDays);

        log.info("回测完成: 有效天数={}/{}, 总交易={}, 单笔胜率={}%, 每日胜率={}%, 总盈亏={}U",
                dailyResults.size(), days, totalTrades, result.getWinRate(), result.getDailyWinRate(),
                result.getTotalProfit());
        return result;
    }

    private List<CoinPrice> findClosestPricesForBacktest(LocalDateTime targetTime, int toleranceMinutes) {
        List<CoinPrice> prices = coinPriceRepository.findByTimestamp(targetTime);
        if (!prices.isEmpty())
            return prices;

        LocalDateTime searchStart = targetTime.minusMinutes(toleranceMinutes);
        List<CoinPrice> beforePrices = coinPriceRepository.findLatestPricesBefore(targetTime);
        if (!beforePrices.isEmpty()) {
            LocalDateTime foundTime = beforePrices.get(0).getTimestamp();
            if (!foundTime.isBefore(searchStart))
                return beforePrices;
        }

        LocalDateTime searchEnd = targetTime.plusMinutes(toleranceMinutes);
        List<CoinPrice> afterPrices = coinPriceRepository.findEarliestPricesAfter(targetTime);
        if (!afterPrices.isEmpty()) {
            LocalDateTime foundTime = afterPrices.get(0).getTimestamp();
            if (!foundTime.isAfter(searchEnd))
                return afterPrices;
        }
        return Collections.emptyList();
    }

    /**
     * 带缓存的价格查找（用于回测优化，显著减少DB查询）
     */
    private List<CoinPrice> findClosestPricesForBacktestCached(LocalDateTime targetTime, int toleranceMinutes) {
        return backtestPriceCache.get(targetTime, k -> findClosestPricesForBacktest(k, toleranceMinutes));
    }

    /**
     * 回补历史5分钟K线价格数据（专供策略优化器使用）
     * 
     * 此方法仅保存价格数据到 coin_price 表，不会影响基准价格。
     * 已存在的数据会自动跳过，不会重复保存。
     *
     * @param days 回补多少天的数据（从当前时间往前推）
     * @return 回补结果统计
     */
    public Map<String, Object> backfillPriceDataForOptimizer(int days) {
        Map<String, Object> result = new HashMap<>();

        if (days <= 0 || days > 365) {
            result.put("success", false);
            result.put("error", "天数必须在 1-365 之间");
            return result;
        }

        LocalDateTime endTime = LocalDateTime.now(ZoneId.of("UTC"));
        LocalDateTime startTime = endTime.minusDays(days);

        log.info("开始回补价格数据: {} 到 {} (共 {} 天)", startTime, endTime, days);

        long startMs = System.currentTimeMillis();

        // 调用 backfillPhaseV2，明确禁用基准价收集
        // collectBasePrices = false 确保不会影响任何基准价
        // 并发数使用 5（中等并发，平衡速度与API限制）
        backfillPhaseV2(startTime, endTime, 5, false);

        long elapsed = System.currentTimeMillis() - startMs;

        result.put("success", true);
        result.put("days", days);
        result.put("startTime", startTime.toString());
        result.put("endTime", endTime.toString());
        result.put("elapsedSeconds", elapsed / 1000);
        result.put("message", String.format("价格数据回补完成，共 %d 天，耗时 %d 秒", days, elapsed / 1000));

        log.info("价格数据回补完成，耗时 {} 秒", elapsed / 1000);

        return result;
    }

    public com.binance.index.dto.BacktestResult runShortTopNBacktestApi(
            int entryHour, int entryMinute, double amountPerCoin, int days, int rankingHours, int holdHours,
            int topN, String timezone) {
        return runShortTopNBacktestApi(entryHour, entryMinute, amountPerCoin, days, rankingHours, holdHours, topN,
                timezone, null, false);
    }

    /**
     * 做空涨幅榜前N名回测（API版本）- 性能优化版
     */
    public com.binance.index.dto.BacktestResult runShortTopNBacktestApi(
            int entryHour, int entryMinute, double amountPerCoin, int days, int rankingHours, int holdHours,
            int topN, String timezone, List<String> symbols, boolean skipPreload) {
        return runShortTopNBacktestApi(entryHour, entryMinute, amountPerCoin, days, rankingHours, holdHours, topN,
                timezone, symbols, skipPreload, null);
    }

    /**
     * 做空涨幅榜前N名回测（API版本）- 极致性能版 (支持外部共享价格图)
     */
    public com.binance.index.dto.BacktestResult runShortTopNBacktestApi(
            int entryHour, int entryMinute, double amountPerCoin, int days, int rankingHours, int holdHours,
            int topN, String timezone, List<String> symbols, boolean skipPreload,
            Map<java.time.LocalDateTime, Map<String, Double>> sharedPriceMap) {

        if (klineService == null) {
            log.error("KlineService未初始化，无法使用API回测");
            com.binance.index.dto.BacktestResult errorResult = new com.binance.index.dto.BacktestResult();
            errorResult.setTotalDays(0);
            errorResult.setValidDays(0);
            errorResult.setSkippedDays(java.util.List.of("KlineService未初始化"));
            return errorResult;
        }

        if (!skipPreload) {
            log.info("开始做空涨幅榜前{}名回测(API版): 入场时间={}:{}, 每币金额={}U, 回测{}天, 涨幅榜{}小时, 持仓{}小时, 时区={}",
                    topN, entryHour, entryMinute, amountPerCoin, days, rankingHours, holdHours, timezone);
        }

        java.time.ZoneId userZone = java.time.ZoneId.of(timezone);
        java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");

        java.time.LocalDate today = java.time.LocalDate.now(userZone);
        java.time.LocalDate endDate = today.minusDays(1);
        java.time.LocalDate startDate = endDate.minusDays(days - 1);

        log.info("回测日期范围: {} 至 {}", startDate, endDate);

        // --- 优化：预加载所有需要的K线数据 ---
        // 1. 获取所有交易对
        List<String> finalSymbols;
        if (skipPreload && symbols != null) {
            finalSymbols = symbols;
        } else {
            long startSymbolsTime = System.currentTimeMillis();
            finalSymbols = binanceApiService.getAllUsdtSymbols();
            log.info("📊 获取 {} 个交易对耗时: {}ms", finalSymbols.size(), (System.currentTimeMillis() - startSymbolsTime));
        }

        if (finalSymbols.isEmpty()) {
            log.warn("无法获取交易对列表，回测终止");
            com.binance.index.dto.BacktestResult errorResult = new com.binance.index.dto.BacktestResult();
            errorResult.setSkippedDays(java.util.List.of("无法获取交易对列表"));
            return errorResult;
        }

        // 2. 预加载时间范围（基准时间到结束时间）
        java.time.LocalDateTime preloadStart = startDate.atTime(entryHour, entryMinute).minusHours(rankingHours + 1);
        java.time.LocalDateTime preloadEnd = endDate.atTime(entryHour, entryMinute).plusHours(holdHours);

        if (!skipPreload) {
            log.info("🚀 启动数据预拉取器: {} 至 {}", preloadStart, preloadEnd);
            long startPreloadTime = System.currentTimeMillis();
            klineService.preloadKlines(preloadStart, preloadEnd, finalSymbols);
            log.info("⏱️ 数据预拉取完成，总耗时: {}ms", (System.currentTimeMillis() - startPreloadTime));
        }
        // --- 优化结束 ---

        // --- 性能再次优化：一次性从数据库查出所有需要的时间点 ---
        // 使用 openPrice：12:00的K线的openPrice就是12:00那一刻的价格，无需时间偏移
        List<java.time.LocalDateTime> allRequiredTimesUtc = new ArrayList<>();
        for (java.time.LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            java.time.LocalDateTime entryTimeLocal = date.atTime(entryHour, entryMinute);
            java.time.LocalDateTime exitTimeLocal = entryTimeLocal.plusHours(holdHours);
            java.time.LocalDateTime changeBaseTimeLocal = entryTimeLocal.minusHours(rankingHours);

            allRequiredTimesUtc
                    .add(entryTimeLocal.atZone(userZone).withZoneSameInstant(utcZone).toLocalDateTime());
            allRequiredTimesUtc
                    .add(exitTimeLocal.atZone(userZone).withZoneSameInstant(utcZone).toLocalDateTime());
            allRequiredTimesUtc.add(
                    changeBaseTimeLocal.atZone(userZone).withZoneSameInstant(utcZone).toLocalDateTime());
        }

        // 批量获取所有价格
        Map<java.time.LocalDateTime, Map<String, Double>> bulkPriceMap;
        if (sharedPriceMap != null && !sharedPriceMap.isEmpty()) {
            bulkPriceMap = sharedPriceMap;
        } else {
            log.info("🔍 开始批量从数据库查询 {} 个时间点的价格汇总...", allRequiredTimesUtc.size());
            long startBulkTime = System.currentTimeMillis();
            bulkPriceMap = klineService.getBulkPricesAtTimes(allRequiredTimesUtc);
            log.info("⏱️ 数据库价格批量查询完成，耗时: {}ms", (System.currentTimeMillis() - startBulkTime));
        }
        // --- 性能优化结束 ---

        List<com.binance.index.dto.BacktestDailyResult> dailyResults = new ArrayList<>();
        List<String> skippedDays = new ArrayList<>();

        int totalTrades = 0;
        int winTrades = 0;
        int loseTrades = 0;
        int winDays = 0; // 盈利天数（当日总盈亏>0）
        int loseDays = 0; // 亏损天数（当日总盈亏<=0）
        double totalProfit = 0;

        long startLoopTime = System.currentTimeMillis();
        for (java.time.LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            long startDayTime = System.currentTimeMillis();
            java.time.LocalDateTime entryTimeLocal = date.atTime(entryHour, entryMinute);
            java.time.LocalDateTime exitTimeLocal = entryTimeLocal.plusHours(holdHours);

            // 使用 openPrice：12:00的K线的openPrice就是12:00那一刻的价格，无需时间偏移
            java.time.LocalDateTime entryTimeUtcLookup = entryTimeLocal.atZone(userZone).withZoneSameInstant(utcZone)
                    .toLocalDateTime();
            java.time.LocalDateTime exitTimeUtcLookup = exitTimeLocal.atZone(userZone).withZoneSameInstant(utcZone)
                    .toLocalDateTime();
            java.time.LocalDateTime changeBaseTimeUtcLookup = entryTimeLocal.minusHours(rankingHours)
                    .atZone(userZone).withZoneSameInstant(utcZone).toLocalDateTime();

            // 从批量映射中获取价格，不再触发数据库查询
            Map<String, Double> changeBaseMap = bulkPriceMap.getOrDefault(changeBaseTimeUtcLookup, new HashMap<>());
            Map<String, Double> entryMap = bulkPriceMap.getOrDefault(entryTimeUtcLookup, new HashMap<>());
            Map<String, Double> exitMap = bulkPriceMap.getOrDefault(exitTimeUtcLookup, new HashMap<>());

            if (changeBaseMap.isEmpty() || entryMap.isEmpty() || exitMap.isEmpty()) {
                log.warn("日期 {} 数据不完整 (BaseLookup:{}, EntryLookup:{}, ExitLookup:{})，跳过",
                        date, changeBaseMap.size(), entryMap.size(), exitMap.size());
                skippedDays.add(date.toString());
                continue;
            }

            // 计算涨幅并排序
            long startRankTime = System.currentTimeMillis();
            List<Map.Entry<String, Double>> changeList = new ArrayList<>();
            for (Map.Entry<String, Double> entry : entryMap.entrySet()) {
                String symbol = entry.getKey();
                Double entryPrice = entry.getValue();
                Double basePrice = changeBaseMap.get(symbol);
                if (basePrice != null && basePrice > 0 && entryPrice != null && entryPrice > 0) {
                    double changePercent = (entryPrice - basePrice) / basePrice * 100;
                    changeList.add(new java.util.AbstractMap.SimpleEntry<>(symbol, changePercent));
                }
            }

            changeList.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<Map.Entry<String, Double>> topCoins = changeList.stream().limit(topN).collect(Collectors.toList());
            long rankElapsed = System.currentTimeMillis() - startRankTime;

            if (topCoins.isEmpty()) {
                skippedDays.add(date.toString());
                continue;
            }

            List<com.binance.index.dto.BacktestTrade> trades = new ArrayList<>();
            double dailyProfit = 0;
            int dailyWin = 0;
            int dailyLose = 0;

            for (Map.Entry<String, Double> entry : topCoins) {
                String symbol = entry.getKey();
                Double change24h = entry.getValue();
                Double entryPrice = entryMap.get(symbol);
                Double exitPrice = exitMap.get(symbol);

                if (entryPrice == null || exitPrice == null || entryPrice <= 0)
                    continue;

                double profitPercent = (entryPrice - exitPrice) / entryPrice * 100;
                double profit = amountPerCoin * profitPercent / 100;

                trades.add(new com.binance.index.dto.BacktestTrade(
                        symbol, entryPrice, exitPrice, change24h,
                        Math.round(profit * 100) / 100.0,
                        Math.round(profitPercent * 100) / 100.0));

                dailyProfit += profit;
                if (profit > 0) {
                    dailyWin++;
                    winTrades++;
                } else {
                    dailyLose++;
                    loseTrades++;
                }
                totalTrades++;
            }

            totalProfit += dailyProfit;

            // 统计每日胜率
            if (dailyProfit > 0) {
                winDays++;
            } else {
                loseDays++;
            }

            // 使用setter创建每日结果
            com.binance.index.dto.BacktestDailyResult dailyResult = new com.binance.index.dto.BacktestDailyResult();
            dailyResult.setDate(date.toString());
            dailyResult.setEntryTime(entryTimeLocal.toString());
            dailyResult.setExitTime(exitTimeLocal.toString());
            dailyResult.setTotalProfit(Math.round(dailyProfit * 100) / 100.0);
            dailyResult.setWinCount(dailyWin);
            dailyResult.setLoseCount(dailyLose);
            dailyResult.setTrades(trades);
            dailyResults.add(dailyResult);

            if (days <= 7) {
                log.debug("📅 日期 {} 计算完成，耗时: {}ms (排名耗时: {}ms)", date, (System.currentTimeMillis() - startDayTime),
                        rankElapsed);
            }
        }
        log.info("⏱️ 回测循环执行完成，总耗时: {}ms", (System.currentTimeMillis() - startLoopTime));

        // 使用setter创建总结果
        com.binance.index.dto.BacktestResult result = new com.binance.index.dto.BacktestResult();
        result.setTotalDays(days);
        result.setValidDays(dailyResults.size());
        result.setTotalTrades(totalTrades);
        result.setWinTrades(winTrades);
        result.setLoseTrades(loseTrades);
        result.setWinRate(totalTrades > 0 ? Math.round(winTrades * 10000.0 / totalTrades) / 100.0 : 0);
        result.setWinDays(winDays);
        result.setLoseDays(loseDays);
        result.setDailyWinRate(
                dailyResults.size() > 0 ? Math.round(winDays * 10000.0 / dailyResults.size()) / 100.0 : 0);

        // 计算每月胜率（30天为一个月）
        List<BacktestMonthlyResult> monthlyDetail = new ArrayList<>();
        int winMonths = 0;
        int loseMonths = 0;
        double monthProfit = 0;
        int mWinDays = 0;
        int mLoseDays = 0;
        int monthCount = 0;

        for (int i = 0; i < dailyResults.size(); i++) {
            com.binance.index.dto.BacktestDailyResult dr = dailyResults.get(i);
            monthProfit += dr.getTotalProfit();
            if (dr.getTotalProfit() > 0)
                mWinDays++;
            else if (dr.getTotalProfit() < 0)
                mLoseDays++;

            // 每30天结算一次，或者到达最后一天
            if ((i + 1) % 30 == 0 || i == dailyResults.size() - 1) {
                monthCount++;
                BacktestMonthlyResult mr = new BacktestMonthlyResult();
                mr.setMonthLabel("第 " + monthCount + " 个月");
                mr.setTotalProfit(Math.round(monthProfit * 100) / 100.0);
                mr.setWinDays(mWinDays);
                mr.setLoseDays(mLoseDays);
                monthlyDetail.add(mr);

                if (monthProfit > 0) {
                    winMonths++;
                } else {
                    loseMonths++;
                }
                monthProfit = 0; // 重置月度累计
                mWinDays = 0;
                mLoseDays = 0;
            }
        }
        int totalMonths = winMonths + loseMonths;
        result.setWinMonths(winMonths);
        result.setLoseMonths(loseMonths);
        result.setMonthlyWinRate(totalMonths > 0 ? Math.round(winMonths * 10000.0 / totalMonths) / 100.0 : 0);
        result.setMonthlyResults(monthlyDetail);

        result.setTotalProfit(Math.round(totalProfit * 100) / 100.0);
        result.setDailyResults(dailyResults);
        result.setSkippedDays(skippedDays);

        log.info("API回测完成: 总交易{}笔, 盈利{}笔, 亏损{}笔, 单笔胜率{}%, 每日胜率{}%, 总盈亏{}U",
                totalTrades, winTrades, loseTrades, result.getWinRate(), result.getDailyWinRate(),
                result.getTotalProfit());

        return result;
    }
}