package com.binance.index.service;

import com.binance.index.dto.KlineData;
import com.binance.index.entity.HourlyKline;
import com.binance.index.repository.HourlyKlineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * K线数据服务 - 用于回测的历史数据获取
 * 1. 优先从本地数据库读取缓存
 * 2. 缓存未命中时从币安API获取并存入数据库
 */
@Service
public class KlineService {

    private static final Logger log = LoggerFactory.getLogger(KlineService.class);

    @Autowired
    private HourlyKlineRepository hourlyKlineRepository;

    @Autowired
    private BinanceApiService binanceApiService;

    /**
     * 获取 HourlyKlineRepository（用于外部查询）
     */
    public HourlyKlineRepository getHourlyKlineRepository() {
        return hourlyKlineRepository;
    }

    /**
     * 获取指定时间点所有币种的收盘价（用于回测）
     * 会自动处理数据缓存
     * 
     * @param targetTime 目标时间点（UTC）
     * @return Map<symbol, closePrice>
     */
    public Map<String, Double> getPricesAtTime(LocalDateTime targetTime) {
        // 对齐到整点小时
        LocalDateTime alignedTime = targetTime.withMinute(0).withSecond(0).withNano(0);

        // 先从本地数据库查询
        List<HourlyKline> localKlines = hourlyKlineRepository.findByOpenTime(alignedTime);

        if (!localKlines.isEmpty()) {
            log.debug("从本地缓存获取 {} 个币种在 {} 的价格", localKlines.size(), alignedTime);
            return localKlines.stream()
                    .collect(Collectors.toMap(
                            HourlyKline::getSymbol,
                            HourlyKline::getClosePrice,
                            (a, b) -> a));
        }

        // 本地没有，需要从API获取
        log.info("本地缓存未命中 {}，需要从币安API获取", alignedTime);
        return fetchAndCacheFromApi(alignedTime);
    }

    /**
     * 从币安API获取数据并缓存到本地
     */
    @Transactional
    public Map<String, Double> fetchAndCacheFromApi(LocalDateTime targetTime) {
        Map<String, Double> prices = new HashMap<>();

        // 获取所有交易对
        List<String> symbols = binanceApiService.getAllUsdtSymbols();
        if (symbols.isEmpty()) {
            log.warn("获取交易对列表失败");
            return prices;
        }

        log.info("开始从币安API获取 {} 个币种在 {} 的K线数据...", symbols.size(), targetTime);

        long targetTimeMs = targetTime.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();
        // 获取整点时间的K线，endTime设置为下一小时开始前
        long endTimeMs = targetTimeMs + 3600000 - 1;

        List<HourlyKline> klinesToSave = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String symbol : symbols) {
            try {
                // 获取1小时K线
                List<KlineData> klines = binanceApiService.getKlines(symbol, "1h", targetTimeMs, endTimeMs, 1);

                if (!klines.isEmpty()) {
                    KlineData kline = klines.get(0);

                    HourlyKline hourlyKline = new HourlyKline(
                            symbol,
                            kline.getTimestamp(),
                            kline.getOpenPrice(),
                            kline.getHighPrice(),
                            kline.getLowPrice(),
                            kline.getClosePrice(),
                            kline.getVolume());
                    klinesToSave.add(hourlyKline);
                    prices.put(symbol, kline.getClosePrice());
                    successCount++;
                } else {
                    failCount++;
                }

                // 请求间隔
                Thread.sleep(binanceApiService.getRequestIntervalMs());

            } catch (Exception e) {
                log.debug("获取 {} K线失败: {}", symbol, e.getMessage());
                failCount++;
            }
        }

        // 批量保存到数据库
        if (!klinesToSave.isEmpty()) {
            try {
                hourlyKlineRepository.saveAll(klinesToSave);
                log.info("成功缓存 {} 个币种在 {} 的K线数据到本地数据库", klinesToSave.size(), targetTime);
            } catch (Exception e) {
                log.warn("保存K线数据失败（可能已存在）: {}", e.getMessage());
            }
        }

        log.info("API获取完成: 成功 {}, 失败 {}", successCount, failCount);
        return prices;
    }

    public void preloadKlines(LocalDateTime startTime, LocalDateTime endTime, List<String> symbols) {
        log.info("开始预加载K线数据: {} 至 {}, {} 个币种", startTime, endTime, symbols.size());
        long startPreload = System.currentTimeMillis();

        long startTimeMs = startTime.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();
        long endTimeMs = endTime.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();
        long expectedHours = java.time.Duration.between(startTime, endTime).toHours() + 1;

        // 1. 优化：一次性查出所有币种在时间段内的计数
        log.info("🔍 正在检查本地缓存状态...");
        long startCheck = System.currentTimeMillis();
        List<Object[]> counts = hourlyKlineRepository.countBySymbolInRange(startTime, endTime);
        Map<String, Long> symbolCountMap = counts.stream()
                .collect(Collectors.toMap(c -> (String) c[0], c -> (Long) c[1]));
        log.info("⏱️ 缓存状态检查完成, 耗时: {}ms", (System.currentTimeMillis() - startCheck));

        // 阈值优化：如果记录数少于期望值的 100%（允许 2 小时的误差以处理边界），则视为需要同步
        // 原有的 0.9 比例在天数较多时会导致漏掉最近几小时的数据
        List<String> symbolsToFetch = symbols.stream()
                .filter(s -> symbolCountMap.getOrDefault(s, 0L) < expectedHours - 2)
                .collect(Collectors.toList());

        if (symbolsToFetch.isEmpty()) {
            log.info("✅ 所有币种本地数据已就绪，无需从API拉取。预加载总耗时: {}ms", (System.currentTimeMillis() - startPreload));
            return;
        }

        log.info("💡 发现 {} 个币种数据不全，开始从币安API拉取...", symbolsToFetch.size());

        int totalToFetch = symbolsToFetch.size();
        int processed = 0;
        int newKlinesCount = 0;

        for (String symbol : symbolsToFetch) {
            processed++;
            try {
                // 从API获取1小时K线
                List<KlineData> klines = binanceApiService.getKlinesWithPagination(
                        symbol, "1h", startTimeMs, endTimeMs, 1000);

                if (!klines.isEmpty()) {
                    List<HourlyKline> toSave = klines.stream()
                            .map(k -> new HourlyKline(
                                    symbol,
                                    k.getTimestamp(),
                                    k.getOpenPrice(),
                                    k.getHighPrice(),
                                    k.getLowPrice(),
                                    k.getClosePrice(),
                                    k.getVolume()))
                            .collect(Collectors.toList());

                    // 批量查询已有的时间点以防重复
                    List<HourlyKline> existing = hourlyKlineRepository.findBySymbolAndOpenTimeBetweenOrderByOpenTime(
                            symbol, startTime, endTime);
                    Set<LocalDateTime> existingTimes = existing.stream()
                            .map(HourlyKline::getOpenTime)
                            .collect(Collectors.toSet());

                    List<HourlyKline> filteredToSave = toSave.stream()
                            .filter(k -> !existingTimes.contains(k.getOpenTime()))
                            .collect(Collectors.toList());

                    if (!filteredToSave.isEmpty()) {
                        hourlyKlineRepository.saveAll(filteredToSave);
                        newKlinesCount += filteredToSave.size();
                    }
                }

                if (processed % 20 == 0 || processed == totalToFetch) {
                    log.info("API拉取进度: {}/{} - {} 完成", processed, totalToFetch, symbol);
                }

                Thread.sleep(500); // 防限流保护
            } catch (Exception e) {
                log.warn("进度: {}/{} - {} 拉取失败: {}", processed, totalToFetch, symbol, e.getMessage());
            }
        }

        log.info("🎉 K线数据预加载成功！共计拉取 {} 个币种，新增保存 {} 条K线数据。总耗时: {}ms",
                totalToFetch, newKlinesCount, (System.currentTimeMillis() - startPreload));

        // 关键修复：数据更新后彻底失效内存缓存状态，确保下次查询时重新校准范围并抓取最新数据
        this.priceCache = null;
        this.cachedStart = null;
        this.cachedEnd = null;
    }

    /**
     * 批量获取多个时间点的所有价格数据
     * 用于优化回测性能，将数百次数据库查询减少为一次
     * 
     * @param times 需要查询的时间点集合
     * @return Map<时间点, Map<币种, 价格>>
     */
    // --- 内存缓存优化 ---
    // 缓存最近一次大查询的数据范围和结果，避免同一天内重复复盘导致的 5s 等待
    private LocalDateTime cachedStart;
    private LocalDateTime cachedEnd;
    private Map<LocalDateTime, Map<String, Double>> priceCache;

    public synchronized Map<LocalDateTime, Map<String, Double>> getBulkPricesAtTimes(
            java.util.Collection<LocalDateTime> times) {
        if (times == null || times.isEmpty()) {
            return new HashMap<>();
        }

        LocalDateTime minReq = java.util.Collections.min(times);
        LocalDateTime maxReq = java.util.Collections.max(times);

        // 检查缓存逻辑：如果请求范围被现有缓存全覆盖，直接从内存取
        if (priceCache != null && cachedStart != null && cachedEnd != null &&
                !minReq.isBefore(cachedStart) && !maxReq.isAfter(cachedEnd)) {

            log.info("🎯 命中全局缓存! 现有范围: {} 至 {}. 正在提取 {} 个点...",
                    cachedStart, cachedEnd, times.size());

            Map<LocalDateTime, Map<String, Double>> result = new HashMap<>();
            int hitCount = 0;
            for (LocalDateTime t : times) {
                if (priceCache.containsKey(t)) {
                    result.put(t, priceCache.get(t));
                    hitCount++;
                }
            }
            log.info("⚡ 内存提取成功 (命中率: {}/{}). 耗时: 0ms", hitCount, times.size());
            return result;
        }

        int totalSize = times.size();
        log.info("🚀 缓存未命中或超界，启动高性能批量查询 ({} 个点)...", totalSize);
        long startTotal = System.currentTimeMillis();

        // 优化：对于大批量查询，自动将范围对齐到自然日的开始和结束 (00:00:00)
        // 这样可以确保优化器和每日战报在同一天范围内能获得完全一样的边界，极大提高互命率
        LocalDateTime alignedStart = minReq.withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime alignedEnd = maxReq.withHour(23).withMinute(59).withSecond(59);

        log.info("🔍 执行[自然日对齐]投影查询: {} 至 {}", alignedStart, alignedEnd);
        List<Object[]> rows = hourlyKlineRepository.findAllPartialByOpenTimeBetween(alignedStart, alignedEnd);
        long queryElapsed = System.currentTimeMillis() - startTotal;

        log.info("✅ DB投影查询完成: 获得 {} 条记录, 耗时: {}ms", rows.size(), queryElapsed);

        // 更新全局缓存
        Map<LocalDateTime, Map<String, Double>> newCache = new HashMap<>();
        for (Object[] row : rows) {
            String symbol = (String) row[0];
            LocalDateTime time = (LocalDateTime) row[1];
            Double price = (Double) row[2];
            newCache.computeIfAbsent(time, k -> new HashMap<>()).put(symbol, price);
        }

        this.priceCache = newCache;
        this.cachedStart = alignedStart;
        this.cachedEnd = alignedEnd;

        // 过滤出请求需要的点返回
        Map<LocalDateTime, Map<String, Double>> result = new HashMap<>();
        for (LocalDateTime t : times) {
            if (newCache.containsKey(t)) {
                result.put(t, newCache.get(t));
            }
        }

        log.info("📊 批量查询总计性能: DB扫描 {} 条 -> 缓存构建完毕。总耗时: {}ms",
                rows.size(), (System.currentTimeMillis() - startTotal));

        return result;
    }

    /**
     * 获取指定币种在指定时间范围内的涨幅
     * 
     * @param symbol      币种
     * @param baseTime    基准时间（计算涨幅的起点）
     * @param currentTime 当前时间（计算涨幅的终点）
     * @return 涨幅百分比，失败返回null
     */
    public Double getChangePercent(String symbol, LocalDateTime baseTime, LocalDateTime currentTime) {
        LocalDateTime alignedBaseTime = baseTime.withMinute(0).withSecond(0).withNano(0);
        LocalDateTime alignedCurrentTime = currentTime.withMinute(0).withSecond(0).withNano(0);

        Optional<HourlyKline> baseKline = hourlyKlineRepository.findBySymbolAndOpenTime(symbol, alignedBaseTime);
        Optional<HourlyKline> currentKline = hourlyKlineRepository.findBySymbolAndOpenTime(symbol, alignedCurrentTime);

        if (baseKline.isPresent() && currentKline.isPresent()) {
            double basePrice = baseKline.get().getClosePrice();
            double currentPrice = currentKline.get().getClosePrice();

            if (basePrice > 0) {
                return (currentPrice - basePrice) / basePrice * 100;
            }
        }

        return null;
    }

    /**
     * 清空本地所有K线缓存
     */
    @Transactional
    public void clearCache() {
        log.info("正在清空本地 K 线缓存数据...");
        long count = hourlyKlineRepository.count();
        hourlyKlineRepository.deleteAllInBatch();

        // 关键修复：彻底清理内存中的缓存状态，防止残留数据影响下次查询
        this.priceCache = null;
        this.cachedStart = null;
        this.cachedEnd = null;

        log.info("本地 K 线缓存已清空，共删除 {} 条记录", count);
    }
}
