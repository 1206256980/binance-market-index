package com.binance.index.service;

import com.binance.index.dto.KlineData;
import com.binance.index.entity.HourlyKline;
import com.binance.index.repository.HourlyKlineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /**
     * 预加载指定日期范围的所有K线数据
     * 用于回测前预先缓存数据，提高回测速度
     */
    @Transactional
    public void preloadKlines(LocalDateTime startTime, LocalDateTime endTime, List<String> symbols) {
        log.info("开始预加载K线数据: {} 至 {}, {} 个币种", startTime, endTime, symbols.size());

        long startTimeMs = startTime.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();
        long endTimeMs = endTime.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();

        int totalSymbols = symbols.size();
        int processed = 0;

        for (String symbol : symbols) {
            processed++;

            try {
                // 检查本地是否已有足够数据
                long existingCount = hourlyKlineRepository.countBySymbolAndTimeRange(symbol, startTime, endTime);
                long expectedCount = (endTimeMs - startTimeMs) / 3600000; // 预期的小时数

                if (existingCount >= expectedCount * 0.9) {
                    log.debug("[{}/{}] {} 已有足够缓存数据，跳过", processed, totalSymbols, symbol);
                    continue;
                }

                log.info("[{}/{}] 正在获取 {} 的历史K线...", processed, totalSymbols, symbol);

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

                    // 逐条保存，忽略重复
                    int saved = 0;
                    for (HourlyKline kline : toSave) {
                        try {
                            if (!hourlyKlineRepository.findBySymbolAndOpenTime(
                                    kline.getSymbol(), kline.getOpenTime()).isPresent()) {
                                hourlyKlineRepository.save(kline);
                                saved++;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    log.debug("[{}/{}] {} 保存了 {} 条K线", processed, totalSymbols, symbol, saved);
                }

            } catch (Exception e) {
                log.warn("[{}/{}] {} 获取失败: {}", processed, totalSymbols, symbol, e.getMessage());
            }
        }

        log.info("K线数据预加载完成");
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
}
