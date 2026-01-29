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

                Thread.sleep(50); // 防限流保护
            } catch (Exception e) {
                log.warn("进度: {}/{} - {} 拉取失败: {}", processed, totalToFetch, symbol, e.getMessage());
            }
        }

        log.info("🎉 K线数据预加载成功！共计拉取 {} 个币种，新增保存 {} 条K线数据。总耗时: {}ms",
                totalToFetch, newKlinesCount, (System.currentTimeMillis() - startPreload));
    }

    /**
     * 批量获取多个时间点的所有价格数据
     * 用于优化回测性能，将数百次数据库查询减少为一次
     * 
     * @param times 需要查询的时间点集合
     * @return Map<时间点, Map<币种, 价格>>
     */
    public Map<LocalDateTime, Map<String, Double>> getBulkPricesAtTimes(java.util.Collection<LocalDateTime> times) {
        if (times == null || times.isEmpty()) {
            return new HashMap<>();
        }

        List<LocalDateTime> timeList = new ArrayList<>(times);
        int totalSize = timeList.size();
        int batchSize = 50; // 每批查询50个时间点，防止IN子句过大

        log.info("开始批量从本地查询 {} 个时间点的价格数据 (分批大小: {})...", totalSize, batchSize);
        long startTotal = System.currentTimeMillis();

        List<HourlyKline> allKlines = new ArrayList<>();
        long totalQueryMs = 0;

        for (int i = 0; i < totalSize; i += batchSize) {
            int end = Math.min(i + batchSize, totalSize);
            List<LocalDateTime> batch = timeList.subList(i, end);

            long startQuery = System.currentTimeMillis();
            allKlines.addAll(hourlyKlineRepository.findAllByOpenTimeIn(batch));
            totalQueryMs += (System.currentTimeMillis() - startQuery);
        }

        // 按时间点分组，再按币种分组存价格
        // 使用 openPrice：12:00的K线的openPrice就是12:00那一刻的价格
        long startProcess = System.currentTimeMillis();
        Map<LocalDateTime, Map<String, Double>> result = allKlines.stream()
                .collect(Collectors.groupingBy(
                        HourlyKline::getOpenTime,
                        Collectors.toMap(HourlyKline::getSymbol, HourlyKline::getOpenPrice, (v1, v2) -> v1)));
        long processElapsed = System.currentTimeMillis() - startProcess;

        log.info("本地批量查询完成: 获取到 {} 条K线记录，映射为 {} 个时间点。耗时: 总 {}ms (DB分批查询 {}ms, 内存处理 {}ms)",
                allKlines.size(), result.size(), (System.currentTimeMillis() - startTotal), totalQueryMs,
                processElapsed);
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
        log.info("本地 K 线缓存已清空，共删除 {} 条记录", count);
    }
}
