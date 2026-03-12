package com.binance.index.controller;

import com.binance.index.dto.DistributionData;
import com.binance.index.dto.IndexDataPoint;
import com.binance.index.dto.UptrendData;
import com.binance.index.entity.MarketIndex;
import com.binance.index.service.IndexCalculatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/index")
public class IndexController {

    private static final Logger log = LoggerFactory.getLogger(IndexController.class);
    private final IndexCalculatorService indexCalculatorService;
    private final com.binance.index.scheduler.DataCollectorScheduler dataCollectorScheduler;
    private final com.binance.index.service.KlineService klineService;
    private final com.binance.index.service.BinanceApiService binanceApiService;

    // 限制 uptrend-distribution 接口并发为1的信号量
    private final Semaphore uptrendSemaphore = new Semaphore(1);

    public IndexController(IndexCalculatorService indexCalculatorService,
            com.binance.index.scheduler.DataCollectorScheduler dataCollectorScheduler,
            com.binance.index.service.KlineService klineService,
            com.binance.index.service.BinanceApiService binanceApiService) {
        this.indexCalculatorService = indexCalculatorService;
        this.dataCollectorScheduler = dataCollectorScheduler;
        this.klineService = klineService;
        this.binanceApiService = binanceApiService;
    }

    /**
     * 获取当前市场指数
     */
    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> getCurrentIndex() {
        log.info("------------------------- 开始调用 /current 接口 -------------------------");
        MarketIndex latest = indexCalculatorService.getLatestIndex();

        Map<String, Object> response = new HashMap<>();
        if (latest != null) {
            response.put("success", true);
            response.put("data", toDataPoint(latest));
        } else {
            response.put("success", false);
            response.put("message", "暂无数据");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 获取历史指数数据
     * 
     * @param hours 小时数，默认168小时（7天）
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getHistoryData(
            @RequestParam(defaultValue = "168") int hours) {
        log.info("------------------------- 开始调用 /history 接口 -------------------------");
        List<MarketIndex> historyData = indexCalculatorService.getHistoryData(hours);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", historyData.size());
        response.put("data", historyData.stream()
                .map(this::toDataPoint)
                .collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        log.info("------------------------- 开始调用 /stats 接口 -------------------------");
        MarketIndex latest = indexCalculatorService.getLatestIndex();
        List<MarketIndex> last24h = indexCalculatorService.getHistoryData(24);
        List<MarketIndex> last72h = indexCalculatorService.getHistoryData(72);
        List<MarketIndex> last168h = indexCalculatorService.getHistoryData(168);
        List<MarketIndex> last720h = indexCalculatorService.getHistoryData(720);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);

        Map<String, Object> stats = new HashMap<>();

        // 当前值
        if (latest != null) {
            stats.put("current", latest.getIndexValue());
            stats.put("coinCount", latest.getCoinCount());
            // 返回毫秒时间戳，前端可以直接用 new Date() 解析
            stats.put("lastUpdate", latest.getTimestamp()
                    .atZone(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli());
        }

        // 24小时变化
        if (!last24h.isEmpty() && last24h.size() > 1) {
            double first = last24h.get(0).getIndexValue();
            double last = last24h.get(last24h.size() - 1).getIndexValue();
            stats.put("change24h", last - first);

            // 24小时最高最低
            double max24h = last24h.stream().mapToDouble(MarketIndex::getIndexValue).max().orElse(0);
            double min24h = last24h.stream().mapToDouble(MarketIndex::getIndexValue).min().orElse(0);
            stats.put("high24h", max24h);
            stats.put("low24h", min24h);
        }

        // 3天变化
        if (!last72h.isEmpty() && last72h.size() > 1) {
            double first = last72h.get(0).getIndexValue();
            double last = last72h.get(last72h.size() - 1).getIndexValue();
            stats.put("change3d", last - first);

            // 3天最高最低
            double max3d = last72h.stream().mapToDouble(MarketIndex::getIndexValue).max().orElse(0);
            double min3d = last72h.stream().mapToDouble(MarketIndex::getIndexValue).min().orElse(0);
            stats.put("high3d", max3d);
            stats.put("low3d", min3d);
        }

        // 7天变化
        if (!last168h.isEmpty() && last168h.size() > 1) {
            double first = last168h.get(0).getIndexValue();
            double last = last168h.get(last168h.size() - 1).getIndexValue();
            stats.put("change7d", last - first);

            // 7天最高最低
            double max7d = last168h.stream().mapToDouble(MarketIndex::getIndexValue).max().orElse(0);
            double min7d = last168h.stream().mapToDouble(MarketIndex::getIndexValue).min().orElse(0);
            stats.put("high7d", max7d);
            stats.put("low7d", min7d);
        }

        // 30天变化
        if (!last720h.isEmpty() && last720h.size() > 1) {
            double first = last720h.get(0).getIndexValue();
            double last = last720h.get(last720h.size() - 1).getIndexValue();
            stats.put("change30d", last - first);

            // 30天最高最低
            double max30d = last720h.stream().mapToDouble(MarketIndex::getIndexValue).max().orElse(0);
            double min30d = last720h.stream().mapToDouble(MarketIndex::getIndexValue).min().orElse(0);
            stats.put("high30d", max30d);
            stats.put("low30d", min30d);
        }

        response.put("stats", stats);
        return ResponseEntity.ok(response);
    }

    private IndexDataPoint toDataPoint(MarketIndex index) {
        return new IndexDataPoint(
                index.getTimestamp(),
                index.getIndexValue(),
                index.getTotalVolume(),
                index.getCoinCount(),
                index.getUpCount(),
                index.getDownCount(),
                index.getAdr());
    }

    /**
     * 获取涨幅分布数据
     * 
     * 支持两种模式：
     * 1. 相对时间模式: hours=24 (表示从24小时前到现在)
     * 2. 绝对时间模式: start=2024-12-12 10:05&end=2024-12-12 10:15 (指定时间范围)
     * 
     * 绝对时间模式优先级更高，如果同时传了start/end和hours，使用绝对时间模式
     * 
     * @param hours    相对基准时间（多少小时前），支持小数如0.25表示15分钟，默认168小时（7天）
     * @param start    开始时间（基准价格时间），格式: yyyy-MM-dd HH:mm
     * @param end      结束时间（当前价格时间），格式: yyyy-MM-dd HH:mm
     * @param timezone 输入时间的时区，默认 Asia/Shanghai
     */
    @GetMapping("/distribution")
    public ResponseEntity<Map<String, Object>> getDistribution(
            @RequestParam(defaultValue = "168") double hours,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {
        log.info("------------------------- 开始调用 /distribution 接口 -------------------------");
        Map<String, Object> response = new HashMap<>();

        // 如果提供了 start 和 end，使用绝对时间模式
        if (start != null && end != null && !start.isEmpty() && !end.isEmpty()) {
            try {
                // 解析时间
                java.time.LocalDateTime startLocal = parseDateTime(start);
                java.time.LocalDateTime endLocal = parseDateTime(end);

                // 将输入时间从用户时区转换为UTC
                java.time.ZoneId userZone = java.time.ZoneId.of(timezone);
                java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");

                java.time.LocalDateTime startUtc = startLocal.atZone(userZone).withZoneSameInstant(utcZone)
                        .toLocalDateTime();
                java.time.LocalDateTime endUtc = endLocal.atZone(userZone).withZoneSameInstant(utcZone)
                        .toLocalDateTime();

                // 验证时间范围
                if (startUtc.isAfter(endUtc)) {
                    response.put("success", false);
                    response.put("message", "开始时间不能晚于结束时间");
                    return ResponseEntity.badRequest().body(response);
                }

                DistributionData data = indexCalculatorService.getDistributionByTimeRange(startUtc, endUtc);

                if (data != null) {
                    response.put("success", true);
                    response.put("mode", "timeRange");
                    response.put("inputTimezone", timezone);
                    response.put("inputStart", start);
                    response.put("inputEnd", end);
                    response.put("utcStart", startUtc.toString());
                    response.put("utcEnd", endUtc.toString());
                    response.put("data", data);
                } else {
                    response.put("success", false);
                    response.put("message", "获取分布数据失败，可能指定时间范围内无数据");
                }

                return ResponseEntity.ok(response);

            } catch (Exception e) {
                response.put("success", false);
                response.put("message", "时间格式错误，请使用格式: yyyy-MM-dd HH:mm");
                response.put("error", e.getMessage());
                return ResponseEntity.badRequest().body(response);
            }
        }

        // 否则使用相对时间模式（原逻辑）
        DistributionData data = indexCalculatorService.getDistribution(hours);

        if (data != null) {
            response.put("success", true);
            response.put("mode", "hours");
            response.put("hours", hours);
            response.put("data", data);
        } else {
            response.put("success", false);
            response.put("message", "获取分布数据失败");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 获取单边上行涨幅分布数据
     * 
     * 使用位置比率法 + 横盘检测：
     * - 位置比率 = (当前价 - 起点) / (最高价 - 起点)
     * - 当位置比率 < keepRatio 或 连续N根K线未创新高，波段结束
     * 
     * @param hours            相对基准时间（多少小时前），默认168小时（7天）
     * @param keepRatio        保留比率阈值（如0.75表示回吐25%涨幅时结束），默认0.75
     * @param noNewHighCandles 连续多少根K线未创新高视为横盘结束，默认6
     * @param minUptrend       最小涨幅过滤（百分比），默认4%，低于此值的波段不返回
     * @param start            开始时间，格式: yyyy-MM-dd HH:mm
     * @param end              结束时间，格式: yyyy-MM-dd HH:mm
     * @param timezone         输入时间的时区，默认 Asia/Shanghai
     */
    @GetMapping("/uptrend-distribution")
    public ResponseEntity<Map<String, Object>> getUptrendDistribution(
            @RequestParam(defaultValue = "168") double hours,
            @RequestParam(defaultValue = "0.75") double keepRatio,
            @RequestParam(defaultValue = "6") int noNewHighCandles,
            @RequestParam(defaultValue = "4") double minUptrend,
            @RequestParam(defaultValue = "lowHigh") String priceMode,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {
        log.info("------------------------- 开始调用 /uptrend-distribution 接口 -------------------------");
        log.info("信号量状态: 可用许可数={}, 等待队列长度={}, 线程={}",
                uptrendSemaphore.availablePermits(),
                uptrendSemaphore.getQueueLength(),
                Thread.currentThread().getName());
        Map<String, Object> response = new HashMap<>();

        // 尝试获取信号量，如果获取不到说明有其他请求正在处理
        if (!uptrendSemaphore.tryAcquire()) {
            log.warn("uptrend-distribution 接口正忙，拒绝新请求。信号量可用许可数={}，线程={}",
                    uptrendSemaphore.availablePermits(), Thread.currentThread().getName());
            response.put("success", false);
            response.put("message", "接口正忙，当前有其他请求正在处理中，请稍后再试");
            return ResponseEntity.status(503).body(response);
        }
        log.info("成功获取信号量，开始处理请求，线程={}", Thread.currentThread().getName());

        try {
            // 如果提供了 start 和 end，使用绝对时间模式
            if (start != null && end != null && !start.isEmpty() && !end.isEmpty()) {
                try {
                    java.time.LocalDateTime startLocal = parseDateTime(start);
                    java.time.LocalDateTime endLocal = parseDateTime(end);

                    java.time.ZoneId userZone = java.time.ZoneId.of(timezone);
                    java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");

                    java.time.LocalDateTime startUtc = startLocal.atZone(userZone).withZoneSameInstant(utcZone)
                            .toLocalDateTime();
                    java.time.LocalDateTime endUtc = endLocal.atZone(userZone).withZoneSameInstant(utcZone)
                            .toLocalDateTime();

                    if (startUtc.isAfter(endUtc)) {
                        response.put("success", false);
                        response.put("message", "开始时间不能晚于结束时间");
                        return ResponseEntity.badRequest().body(response);
                    }

                    UptrendData data = indexCalculatorService.getUptrendDistributionByTimeRange(startUtc, endUtc,
                            keepRatio,
                            noNewHighCandles, minUptrend, priceMode);

                    if (data != null) {
                        response.put("success", true);
                        response.put("mode", "timeRange");
                        response.put("keepRatio", keepRatio);
                        response.put("noNewHighCandles", noNewHighCandles);
                        response.put("minUptrend", minUptrend);
                        response.put("priceMode", priceMode);
                        response.put("inputTimezone", timezone);
                        response.put("inputStart", start);
                        response.put("inputEnd", end);
                        response.put("data", data);
                    } else {
                        response.put("success", false);
                        response.put("message", "获取单边涨幅数据失败，可能指定时间范围内无数据或无符合条件的波段");
                    }

                    return ResponseEntity.ok(response);

                } catch (Exception e) {
                    response.put("success", false);
                    response.put("message", "时间格式错误，请使用格式: yyyy-MM-dd HH:mm");
                    response.put("error", e.getMessage());
                    return ResponseEntity.badRequest().body(response);
                }
            }

            // 否则使用相对时间模式
            UptrendData data = indexCalculatorService.getUptrendDistribution(hours, keepRatio, noNewHighCandles,
                    minUptrend,
                    priceMode);

            if (data != null) {
                response.put("success", true);
                response.put("mode", "hours");
                response.put("hours", hours);
                response.put("keepRatio", keepRatio);
                response.put("noNewHighCandles", noNewHighCandles);
                response.put("minUptrend", minUptrend);
                response.put("data", data);
            } else {
                response.put("success", false);
                response.put("message", "获取单边涨幅数据失败");
            }

            return ResponseEntity.ok(response);
        } finally {
            // 确保释放信号量
            uptrendSemaphore.release();
            log.info("释放信号量完成，当前可用许可数={}，线程={}",
                    uptrendSemaphore.availablePermits(), Thread.currentThread().getName());
        }
    }

    /**
     * 调试接口：查询指定币种的历史价格数据
     * 
     * @param symbol 币种符号，如 SOLUSDT
     * @param hours  查询多少小时的数据，默认1小时
     */
    @GetMapping("/debug/prices")
    public ResponseEntity<Map<String, Object>> debugPrices(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1") double hours) {
        log.info("------------------------- 开始调用 /debug/prices 接口 -------------------------");
        java.time.LocalDateTime startTime = java.time.LocalDateTime.now().minusMinutes((long) (hours * 60));
        List<com.binance.index.entity.CoinPrice> prices = indexCalculatorService.getCoinPriceHistory(symbol, startTime);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("symbol", symbol);
        response.put("queryStartTime", startTime.toString());
        response.put("count", prices.size());
        response.put("data", prices.stream().map(p -> {
            Map<String, Object> item = new HashMap<>();
            item.put("timestamp", p.getTimestamp().toString());
            // 添加东八区时间字段
            java.time.ZonedDateTime cnTime = p.getTimestamp().atZone(java.time.ZoneId.of("UTC"))
                    .withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"));
            item.put("timestampCN", cnTime.toLocalDateTime().toString());
            item.put("openPrice", p.getOpenPrice());
            item.put("highPrice", p.getHighPrice());
            item.put("lowPrice", p.getLowPrice());
            item.put("closePrice", p.getPrice());
            return item;
        }).collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    /**
     * 调试接口：查询所有基准价格
     */
    @GetMapping("/debug/basePrices")
    public ResponseEntity<Map<String, Object>> debugBasePrices() {
        log.info("------------------------- 开始调用 /debug/basePrices 接口 -------------------------");
        List<com.binance.index.entity.BasePrice> basePrices = indexCalculatorService.getAllBasePrices();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", basePrices.size());

        if (!basePrices.isEmpty()) {
            response.put("createdAt", basePrices.get(0).getCreatedAt().toString());
        }

        response.put("data", basePrices.stream().map(p -> {
            Map<String, Object> item = new HashMap<>();
            item.put("symbol", p.getSymbol());
            item.put("price", p.getPrice());
            item.put("createdAt", p.getCreatedAt().toString());
            return item;
        }).collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    /**
     * 调试接口：验证指数计算
     * 使用全局基准价格（内存中的basePrices）和数据库最新价格进行计算
     * 无需任何参数，直接验证当前实时指数计算是否正确
     */
    @GetMapping("/debug/verify")
    public ResponseEntity<Map<String, Object>> debugVerifyIndex() {
        log.info("------------------------- 开始调用 /debug/verify 接口 -------------------------");
        Map<String, Object> response = indexCalculatorService.verifyIndexCalculation();
        return ResponseEntity.ok(response);
    }

    /**
     * 删除指定时间范围内的数据（用于清理污染数据）
     * 时间格式: yyyy-MM-dd HH:mm 或 yyyy-MM-ddTHH:mm:ss
     * timezone: 输入时间的时区，默认 Asia/Shanghai（东八区），数据库存的是UTC
     * 示例: DELETE /api/index/data?start=2025-12-21 10:00&end=2025-12-21
     * 10:30&timezone=Asia/Shanghai
     */
    @DeleteMapping("/data")
    public ResponseEntity<Map<String, Object>> deleteDataInRange(
            @RequestParam String start,
            @RequestParam String end,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {
        log.info("------------------------- 开始调用 DELETE /data 接口 -------------------------");
        Map<String, Object> response = new HashMap<>();

        try {
            // 解析时间，支持多种格式
            java.time.LocalDateTime startLocal = parseDateTime(start);
            java.time.LocalDateTime endLocal = parseDateTime(end);

            // 将输入时间从用户时区转换为UTC（数据库存的是UTC）
            java.time.ZoneId userZone = java.time.ZoneId.of(timezone);
            java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");

            java.time.LocalDateTime startUtc = startLocal.atZone(userZone).withZoneSameInstant(utcZone)
                    .toLocalDateTime();
            java.time.LocalDateTime endUtc = endLocal.atZone(userZone).withZoneSameInstant(utcZone).toLocalDateTime();

            // 验证时间范围
            if (startUtc.isAfter(endUtc)) {
                response.put("success", false);
                response.put("message", "开始时间不能晚于结束时间");
                return ResponseEntity.badRequest().body(response);
            }

            // 执行删除
            Map<String, Object> result = indexCalculatorService.deleteDataInRange(startUtc, endUtc);

            response.put("success", true);
            response.put("message", "数据删除成功");
            response.put("inputTimezone", timezone);
            response.put("inputStart", start);
            response.put("inputEnd", end);
            response.put("utcStart", startUtc.toString());
            response.put("utcEnd", endUtc.toString());
            response.putAll(result);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "时间格式错误，请使用格式: yyyy-MM-dd HH:mm 或 yyyy-MM-ddTHH:mm:ss");
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 删除指定币种的所有数据（包括历史价格和基准价格）
     * 用于手动清理问题币种的数据
     * 
     * 示例: DELETE /api/index/symbol/XXXUSDT
     * 
     * @param symbol 币种符号，如 SOLUSDT
     */
    @DeleteMapping("/symbol/{symbol}")
    public ResponseEntity<Map<String, Object>> deleteSymbolData(@PathVariable String symbol) {
        log.info("------------------------- 开始调用 DELETE /symbol/{} 接口 -------------------------", symbol);
        Map<String, Object> response = new HashMap<>();

        if (symbol == null || symbol.isEmpty()) {
            response.put("success", false);
            response.put("message", "币种符号不能为空");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            Map<String, Object> result = indexCalculatorService.deleteSymbolData(symbol.toUpperCase());
            response.put("success", true);
            response.put("message", "币种数据删除成功");
            response.putAll(result);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 查询缺漏的历史价格时间点（只查询，不修复）
     * 返回指定时间范围内，数据库中缺失的5分钟K线时间点列表
     * 
     * 示例:
     * - GET /api/index/missing?days=7 （查询最近7天的缺漏）
     * - GET /api/index/missing?start=2024-12-20 00:00&end=2024-12-24 00:00 （指定时间范围）
     * 
     * @param days     检查最近多少天的数据，默认7天（当 start 为空时使用）
     * @param start    开始时间（可选），格式: yyyy-MM-dd HH:mm
     * @param end      结束时间（可选），格式: yyyy-MM-dd HH:mm
     * @param timezone 输入时间的时区，默认 Asia/Shanghai
     */
    @GetMapping("/missing")
    public ResponseEntity<Map<String, Object>> getMissingTimestamps(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {
        log.info("------------------------- 开始调用 /missing 接口 -------------------------");
        Map<String, Object> response = new HashMap<>();

        try {
            java.time.LocalDateTime startTime;
            java.time.LocalDateTime endTime;
            java.time.ZoneId userZone = java.time.ZoneId.of(timezone);
            java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");

            // 计算时间范围
            if (start != null && !start.isEmpty() && end != null && !end.isEmpty()) {
                // 使用指定时间范围
                java.time.LocalDateTime startLocal = parseDateTime(start);
                java.time.LocalDateTime endLocal = parseDateTime(end);
                startTime = startLocal.atZone(userZone).withZoneSameInstant(utcZone).toLocalDateTime();
                endTime = endLocal.atZone(userZone).withZoneSameInstant(utcZone).toLocalDateTime();
            } else {
                // 使用 days 参数
                endTime = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
                endTime = endTime.minusMinutes(endTime.getMinute() % 5).withSecond(0).withNano(0); // 对齐到5分钟
                startTime = endTime.minusDays(days);
            }

            // 调用 service 获取缺漏列表
            Map<String, Object> result = indexCalculatorService.getMissingTimestamps(startTime, endTime);

            response.put("success", true);
            response.put("queryRange", Map.of(
                    "startUtc", startTime.toString(),
                    "endUtc", endTime.toString(),
                    "days", days));
            response.putAll(result);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 修复币种的历史价格缺失数据
     * 检测指定币种在时间范围内的数据缺口，并从币安API回补
     * 
     * 示例:
     * - POST /api/index/repair?days=7 （修复所有币种最近7天）
     * - POST /api/index/repair?symbols=BTCUSDT,ETHUSDT&days=7 （只修复指定币种）
     * - POST /api/index/repair?start=2024-12-20 00:00&end=2024-12-24 00:00 （指定时间范围）
     * 
     * @param days    检查最近多少天的数据，默认7天（当 start 为空时使用）
     * @param start   开始时间（可选）
     * @param end     结束时间（可选）
     * @param symbols 要修复的币种列表，逗号分隔，如 "BTCUSDT,ETHUSDT"（可选，不传则修复所有）
     */
    @PostMapping("/repair")
    public ResponseEntity<Map<String, Object>> repairMissingData(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String symbols) {
        log.info("------------------------- 开始调用 POST /repair 接口 -------------------------");
        Map<String, Object> response = new HashMap<>();

        // 验证 days 参数（仅当未指定 start 时使用）
        if (start == null && (days <= 0 || days > 60)) {
            response.put("success", false);
            response.put("message", "days 参数必须在 1-60 之间");
            return ResponseEntity.badRequest().body(response);
        }

        // 解析币种列表
        List<String> symbolList = null;
        if (symbols != null && !symbols.trim().isEmpty()) {
            symbolList = java.util.Arrays.stream(symbols.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
            if (symbolList.isEmpty()) {
                symbolList = null; // 空列表视为修复所有
            } else {
                log.info("指定修复币种: {}", symbolList);
            }
        }

        try {
            // 解析时间参数（按东八区解析，转换为 UTC）
            java.time.LocalDateTime startTime = null;
            java.time.LocalDateTime endTime = null;
            java.time.ZoneId beijingZone = java.time.ZoneId.of("Asia/Shanghai");
            java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");

            if (start != null && !start.isEmpty()) {
                java.time.LocalDateTime beijingTime = parseDateTime(start);
                // 东八区转 UTC（减8小时）
                startTime = beijingTime.atZone(beijingZone).withZoneSameInstant(utcZone).toLocalDateTime();
            }
            if (end != null && !end.isEmpty()) {
                java.time.LocalDateTime beijingTime = parseDateTime(end);
                // 东八区转 UTC（减8小时）
                endTime = beijingTime.atZone(beijingZone).withZoneSameInstant(utcZone).toLocalDateTime();
            }

            Map<String, Object> result = indexCalculatorService.repairMissingPriceData(startTime, endTime, days,
                    symbolList);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "修复失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 清理指定时间范围的所有数据（用于重新回补前清理）
     * 会删除 CoinPrice 和 MarketIndex 表中指定时间范围的数据
     *
     * 用法：
     * - DELETE /api/index/cleanup?days=3 （清理最近3天）
     * - DELETE /api/index/cleanup?hours=12 （清理最近12小时）
     * - DELETE /api/index/cleanup?start=2026-01-02T00:00 （从指定UTC时间开始清理到现在）
     */
    @DeleteMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupData(
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) Double hours,
            @RequestParam(required = false) String start) {
        log.info("------------------------- 开始调用 DELETE /cleanup 接口 -------------------------");
        Map<String, Object> response = new HashMap<>();

        // 计算时间范围
        java.time.LocalDateTime endTime = java.time.LocalDateTime.now(java.time.ZoneId.of("UTC"));
        java.time.LocalDateTime startTime;

        try {
            if (start != null && !start.isEmpty()) {
                // 直接使用传入的 UTC 时间
                startTime = parseDateTime(start);
                log.info("清理指定时间范围的数据 (UTC): {} -> {}", startTime, endTime);
            } else if (hours != null && hours > 0) {
                // 按小时计算
                long totalMinutes = (long) (hours * 60);
                startTime = endTime.minusMinutes(totalMinutes);
                log.info("清理最近 {} 小时的数据: {} -> {}", hours, startTime, endTime);
            } else if (days != null && days > 0) {
                // 按天计算
                startTime = endTime.minusDays(days);
                log.info("清理最近 {} 天的数据: {} -> {}", days, startTime, endTime);
            } else {
                response.put("success", false);
                response.put("message", "请指定 days、hours 或 start 参数");
                return ResponseEntity.badRequest().body(response);
            }

            Map<String, Object> result = indexCalculatorService.cleanupDataInRange(startTime, endTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("清理数据失败", e);
            response.put("success", false);
            response.put("message", "清理失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 解析时间字符串，支持多种格式
     */
    private java.time.LocalDateTime parseDateTime(String dateTimeStr) {
        // 尝试多种格式
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm"
        };

        for (String pattern : patterns) {
            try {
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern(pattern);
                return java.time.LocalDateTime.parse(dateTimeStr, formatter);
            } catch (Exception ignored) {
            }
        }

        // 尝试 ISO 格式
        return java.time.LocalDateTime.parse(dateTimeStr);
    }

    /**
     * 手动触发重新回补数据
     * 会重置采集错误标志，并异步执行回补流程
     * 
     * 使用场景：
     * - 采集出错导致暂停后，修复问题后调用此接口恢复
     * - 手动刷新历史数据
     */
    @PostMapping("/rebackfill")
    public ResponseEntity<Map<String, Object>> rebackfill() {
        log.info("------------------------- 收到重新回补请求 -------------------------");
        Map<String, Object> response = new HashMap<>();

        try {
            dataCollectorScheduler.rebackfill();
            response.put("success", true);
            response.put("message", "已触发重新回补，请查看日志了解进度");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "触发回补失败: " + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 做空涨幅榜前10回测接口
     * 
     * @param entryHour    入场时间（小时，0-23）
     * @param entryMinute  入场时间（分钟，0-59），默认0
     * @param totalAmount  每日投入总金额(U)
     * @param days         回测天数（从昨天往前推），默认30天
     * @param rankingHours 涨幅排行榜时间范围（24/48/72/168小时），默认24
     * @param holdHours    持仓时间（小时），默认24
     * @param topN         做空涨幅榜前N名，默认10
     * @param timezone     时区，默认Asia/Shanghai（东八区）
     */
    @GetMapping("/backtest/short-top10")
    public ResponseEntity<Map<String, Object>> backtestShortTop10(
            @RequestParam int entryHour,
            @RequestParam(defaultValue = "0") int entryMinute,
            @RequestParam double totalAmount,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "24") int rankingHours,
            @RequestParam(defaultValue = "24") int holdHours,
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {
        log.info("------------------------- 开始调用 /backtest/short-top10 接口 -------------------------");
        Map<String, Object> response = new HashMap<>();

        // 参数校验
        if (entryHour < 0 || entryHour > 23) {
            response.put("success", false);
            response.put("message", "entryHour 必须在 0-23 之间");
            return ResponseEntity.badRequest().body(response);
        }
        if (entryMinute < 0 || entryMinute > 59) {
            response.put("success", false);
            response.put("message", "entryMinute 必须在 0-59 之间");
            return ResponseEntity.badRequest().body(response);
        }
        if (totalAmount <= 0) {
            response.put("success", false);
            response.put("message", "totalAmount 必须大于 0");
            return ResponseEntity.badRequest().body(response);
        }
        if (days <= 0 || days > 365) {
            response.put("success", false);
            response.put("message", "days 必须在 1-365 之间");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            // 计算每币金额 = 总金额 / 做空数量
            double amountPerCoin = totalAmount / topN;

            com.binance.index.dto.BacktestResult result;

            // 使用币安API获取历史数据（支持更长时间范围）
            result = indexCalculatorService.runShortTopNBacktestApi(
                    entryHour, entryMinute, amountPerCoin, days, rankingHours, holdHours, topN, timezone, false,
                    null);

            response.put("success", true);
            response.put("params", Map.of(
                    "entryHour", entryHour,
                    "entryMinute", entryMinute,
                    "totalAmount", totalAmount,
                    "amountPerCoin", amountPerCoin,
                    "days", days,
                    "rankingHours", rankingHours,
                    "timezone", timezone));
            Map<String, Object> summary = new HashMap<>();
            summary.put("totalDays", result.getTotalDays());
            summary.put("validDays", result.getValidDays());
            summary.put("totalTrades", result.getTotalTrades());
            summary.put("winTrades", result.getWinTrades());
            summary.put("loseTrades", result.getLoseTrades());
            summary.put("winRate", result.getWinRate());
            summary.put("winDays", result.getWinDays());
            summary.put("loseDays", result.getLoseDays());
            summary.put("dailyWinRate", result.getDailyWinRate());
            summary.put("winMonths", result.getWinMonths());
            summary.put("loseMonths", result.getLoseMonths());
            summary.put("monthlyWinRate", result.getMonthlyWinRate());
            summary.put("totalProfit", result.getTotalProfit());
            summary.put("monthlyResults", result.getMonthlyResults());
            response.put("summary", summary);
            response.put("dailyResults", result.getDailyResults());
            response.put("skippedDays", result.getSkippedDays());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("回测执行失败", e);
            response.put("success", false);
            response.put("message", "回测执行失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 策略优化器 - 遍历所有参数组合找出最优策略
     * 
     * @param totalAmount 每日投入总金额(U)
     * @param days        回测天数（从昨天往前推），默认30天
     * @param timezone    时区，默认Asia/Shanghai
     */
    @GetMapping("/backtest/optimize")
    public ResponseEntity<Map<String, Object>> optimizeStrategy(
            @RequestParam(defaultValue = "1000") double totalAmount,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String entryHours,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone,
            @RequestParam(required = false) String holdHours,
            @RequestParam(required = false) String rankingHours,
            @RequestParam(defaultValue = "100") int limit) {
        log.info("------------------------- 开始调用 /backtest/optimize 接口-------------------------");
        Map<String, Object> response = new HashMap<>();

        if (totalAmount <= 0) {
            response.put("success", false);
            response.put("message", "totalAmount 必须大于 0");
            return ResponseEntity.badRequest().body(response);
        }
        if (days <= 0 || days > 365) {
            response.put("success", false);
            response.put("message", "days 必须在 1-365 之间");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            // 定义参数范围
            int[] rankingHoursOptions;
            if (rankingHours != null && !rankingHours.trim().isEmpty() && !rankingHours.equalsIgnoreCase("all")) {
                try {
                    rankingHoursOptions = java.util.Arrays.stream(rankingHours.split(","))
                            .map(String::trim)
                            .mapToInt(Integer::parseInt)
                            .toArray();
                } catch (Exception e) {
                    rankingHoursOptions = new int[] { 24, 48, 72, 168 };
                }
            } else {
                rankingHoursOptions = new int[] { 24, 48, 72, 168 };
            }
            int[] topNOptions = { 5, 10, 15, 20, 30 };
            int[] entryHourOptions;
            int[] holdHoursOptions;

            // 解析入场时间
            if (entryHours != null && !entryHours.trim().isEmpty()) {
                try {
                    entryHourOptions = java.util.Arrays.stream(entryHours.split(","))
                            .map(String::trim)
                            .mapToInt(Integer::parseInt)
                            .filter(h -> h >= 0 && h <= 23)
                            .distinct()
                            .sorted()
                            .toArray();

                    if (entryHourOptions.length == 0) {
                        entryHourOptions = new int[] { 0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22 };
                    }
                } catch (Exception e) {
                    log.error("解析 entryHours 失败: " + entryHours, e);
                    entryHourOptions = new int[] { 0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22 };
                }
            } else {
                entryHourOptions = new int[] { 0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22 };
            }

            // 解析持仓时间
            if (holdHours != null && !holdHours.trim().isEmpty()) {
                try {
                    holdHoursOptions = java.util.Arrays.stream(holdHours.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .mapToInt(Integer::parseInt)
                            .filter(h -> h > 0)
                            .distinct()
                            .sorted()
                            .toArray();

                    if (holdHoursOptions.length == 0) {
                        holdHoursOptions = new int[] { 24, 48, 72 };
                    }
                } catch (Exception e) {
                    log.error("解析 holdHours 失败: " + holdHours, e);
                    holdHoursOptions = new int[] { 24, 48, 72 };
                }
            } else {
                holdHoursOptions = new int[] { 24, 48, 72 };
            }

            // 预先生成所有参数组合，用于并行处理
            List<int[]> combinations = new java.util.ArrayList<>();
            for (int rHours : rankingHoursOptions) {
                for (int tN : topNOptions) {
                    for (int eHour : entryHourOptions) {
                        for (int hHour : holdHoursOptions) {
                            combinations.add(new int[] { rHours, tN, eHour, hHour });
                        }
                    }
                }
            }

            int totalCombinations = combinations.size();
            long startTime = System.currentTimeMillis();

            // --- 性能极致优化：预加载外提 ---
            Map<java.time.LocalDateTime, Map<String, Double>> sharedPriceMap = null;

            log.info("🚀 优化器检测到使用 API，开始执行全局预加载与价格预取...");
            long startGlobalPreload = System.currentTimeMillis();

            // 2. 找到所有组合中的参数极值
            int maxRankingHours = java.util.Arrays.stream(rankingHoursOptions).max().orElse(24);
            int maxHoldHours = java.util.Arrays.stream(holdHoursOptions).max().orElse(24);

            // 3. 计算全局预加载范围 (用于从数据库批量抓取到内存)
            java.time.ZoneId userZone = java.time.ZoneId.of(timezone);
            java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");
            java.time.LocalDate today = java.time.LocalDate.now(userZone);
            java.time.LocalDate endDate = today;
            java.time.LocalDate startDate = endDate.minusDays(days - 1);

            int minEntryHour = java.util.Arrays.stream(entryHourOptions).min().orElse(0);
            int maxEntryHour = java.util.Arrays.stream(entryHourOptions).max().orElse(23);

            java.time.LocalDateTime globalPreloadStart = startDate.atTime(minEntryHour, 0)
                    .minusHours(maxRankingHours + 1);
            java.time.LocalDateTime globalPreloadEnd = endDate.atTime(maxEntryHour, 0).plusHours(maxHoldHours);

            log.info("📦 启动全局优化器（基于本地缓存数据，预期范围: {} 至 {}）", globalPreloadStart, globalPreloadEnd);

            // 4. 汇总所有组合需要的精确时间点 (用于从本地批量抓取到内存)
            // 使用 openPrice：12:00的K线的openPrice就是12:00那一刻的价格，无需时间偏移
            log.info("🔍 汇总所有参数组合所需的精确时间点...");
            java.util.Set<java.time.LocalDateTime> allRequiredTimesUtc = new java.util.HashSet<>();
            for (java.time.LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                for (int eHour : entryHourOptions) {
                    java.time.LocalDateTime entryTimeUtcLookup = date.atTime(eHour, 0).atZone(userZone)
                            .withZoneSameInstant(utcZone).toLocalDateTime();
                    allRequiredTimesUtc.add(entryTimeUtcLookup);

                    // 由于 holdHours 有多种可能，汇总所有可能
                    for (int hHours : holdHoursOptions) {
                        java.time.LocalDateTime exitTimeUtcLookup = date.atTime(eHour, 0).plusHours(hHours)
                                .atZone(userZone).withZoneSameInstant(utcZone).toLocalDateTime();
                        allRequiredTimesUtc.add(exitTimeUtcLookup);
                    }

                    // 由于 rankingHours 有多种可能，汇总所有可能
                    for (int rHours : rankingHoursOptions) {
                        java.time.LocalDateTime baseTimeUtcLookup = date.atTime(eHour, 0).minusHours(rHours)
                                .atZone(userZone).withZoneSameInstant(utcZone).toLocalDateTime();
                        allRequiredTimesUtc.add(baseTimeUtcLookup);
                    }
                }
            }

            log.info("🔍 共汇总 {} 个全局时间点，开始执行分注批量抓取...", allRequiredTimesUtc.size());
            sharedPriceMap = indexCalculatorService.getKlineService().getBulkPricesAtTimes(allRequiredTimesUtc);
            log.info("⏱️ 全局预取完成，共耗时: {}ms", (System.currentTimeMillis() - startGlobalPreload));

            // --- 优化结束 ---

            // 使用并行流执行回测
            final Map<java.time.LocalDateTime, Map<String, Double>> pricesForTask = sharedPriceMap;
            List<Map<String, Object>> allResults = combinations.parallelStream()
                    .map(combo -> {
                        int rHours = combo[0];
                        int tN = combo[1];
                        int eHour = combo[2];
                        int hHours = combo[3];
                        double amountPerCoin = totalAmount / tN;

                        com.binance.index.dto.BacktestResult backtestResult;

                        // 使用极致优化版，传入预先抓取的全局价格图，实现 0 DB 竞态
                        backtestResult = indexCalculatorService.runShortTopNBacktestApi(
                                eHour, 0, amountPerCoin, days, rHours, hHours, tN, timezone, true,
                                pricesForTask);

                        Map<String, Object> res = new HashMap<>();
                        res.put("rankingHours", rHours);
                        res.put("topN", tN);
                        res.put("entryHour", eHour);
                        res.put("holdHours", hHours);
                        res.put("totalProfit", backtestResult.getTotalProfit());
                        res.put("winRate", backtestResult.getWinRate());
                        res.put("dailyWinRate", backtestResult.getDailyWinRate());
                        res.put("winDays", backtestResult.getWinDays());
                        res.put("loseDays", backtestResult.getLoseDays());
                        res.put("monthlyWinRate", backtestResult.getMonthlyWinRate());
                        res.put("winMonths", backtestResult.getWinMonths());
                        res.put("loseMonths", backtestResult.getLoseMonths());
                        res.put("monthlyResults", backtestResult.getMonthlyResults());
                        res.put("dailyResults", backtestResult.getDailyResults()); // 新增每日明细
                        res.put("totalTrades", backtestResult.getTotalTrades());
                        res.put("validDays", backtestResult.getValidDays());
                        return res;
                    })
                    .collect(java.util.stream.Collectors.toList());

            long endTime = System.currentTimeMillis();

            // 按总盈亏排序，取前10
            allResults.sort((a, b) -> Double.compare(
                    (Double) b.get("totalProfit"),
                    (Double) a.get("totalProfit")));

            List<Map<String, Object>> sortedResults;
            if (limit > 0 && allResults.size() > limit) {
                sortedResults = allResults.subList(0, limit);
            } else {
                sortedResults = allResults;
            }

            response.put("success", true);
            response.put("params", Map.of(
                    "totalAmount", totalAmount,
                    "days", days,
                    "timezone", timezone));
            response.put("totalCombinations", totalCombinations);
            response.put("timeTakenMs", endTime - startTime);
            response.put("topStrategies", sortedResults);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("策略优化失败", e);
            response.put("success", false);
            response.put("message", "策略优化失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 每日策略优化器 - 按日排名展示策略表现
     * 
     * @param totalAmount 每日投入总金额(U)
     * @param days        回测天数
     * @param entryHour   入场时间（单选）
     * @param holdHours   持仓时间（单选）
     * @param timezone    时区
     * @param page        页码（从1开始），默认1
     * @param pageSize    每页天数，默认10
     */
    @GetMapping("/backtest/optimize-daily")
    public ResponseEntity<Map<String, Object>> optimizeStrategyDaily(
            @RequestParam(defaultValue = "1000") double totalAmount,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam String entryHours, // 改为 String 以支持多选 "0,2,4..."
            @RequestParam int holdHours,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String rankingHours,
            @RequestParam(defaultValue = "20") int topNLimit) {
        log.info(
                "------------------------- 开始调用 /backtest/optimize-daily 接口 (entries={}, hold={} h, ranking={}, topNLimit={}, page={}, pageSize={}) -------------------------",
                entryHours, holdHours, rankingHours, topNLimit, page, pageSize);
        Map<String, Object> response = new HashMap<>();

        try {
            // 解析入场时间
            int[] entryHourOptions = java.util.Arrays.stream(entryHours.split(","))
                    .map(String::trim)
                    .mapToInt(Integer::parseInt)
                    .toArray();

            // 解析涨幅榜周期
            int[] rankingHoursOptions;
            if (rankingHours != null && !rankingHours.trim().isEmpty() && !rankingHours.equalsIgnoreCase("all")) {
                try {
                    rankingHoursOptions = java.util.Arrays.stream(rankingHours.split(","))
                            .map(String::trim)
                            .mapToInt(Integer::parseInt)
                            .toArray();
                } catch (Exception e) {
                    rankingHoursOptions = new int[] { 24, 48, 72, 168 };
                }
            } else {
                rankingHoursOptions = new int[] { 24, 48, 72, 168 };
            }
            int[] topNOptions = { 5, 10, 15, 20 };

            // 生成组合 (增加 entryHour 维度)
            List<int[]> combinations = new java.util.ArrayList<>();
            for (int eHour : entryHourOptions) {
                for (int rHours : rankingHoursOptions) {
                    for (int tN : topNOptions) {
                        combinations.add(new int[] { eHour, rHours, tN });
                    }
                }
            }

            long startTime = System.currentTimeMillis();

            // 预加载外提
            Map<java.time.LocalDateTime, Map<String, Double>> sharedPriceMap = null;

            int maxRankingHours = java.util.Arrays.stream(rankingHoursOptions).max().orElse(168);
            java.time.ZoneId userZone = java.time.ZoneId.of(timezone);
            java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");
            java.time.LocalDate today = java.time.LocalDate.now(userZone);
            java.time.LocalDate endDate = today;
            java.time.LocalDate startDate = endDate.minusDays(days - 1);

            // 汇总所有入场时间需要的全局范围
            int minE = java.util.Arrays.stream(entryHourOptions).min().orElse(0);
            int maxE = java.util.Arrays.stream(entryHourOptions).max().orElse(23);
            java.time.LocalDateTime globalPreloadStart = startDate.atTime(minE, 0).minusHours(maxRankingHours + 1);
            java.time.LocalDateTime globalPreloadEnd = endDate.atTime(maxE, 0).plusHours(holdHours);

            log.info("📦 每日优化器(多入场)执行全局价格预取: {} 至 {}", globalPreloadStart, globalPreloadEnd);

            java.util.Set<java.time.LocalDateTime> allRequiredTimesUtc = new java.util.HashSet<>();
            for (java.time.LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                for (int eHour : entryHourOptions) {
                    java.time.LocalDateTime entryTimeUtc = date.atTime(eHour, 0).atZone(userZone)
                            .withZoneSameInstant(utcZone).toLocalDateTime();
                    java.time.LocalDateTime exitTimeUtc = date.atTime(eHour, 0).plusHours(holdHours).atZone(userZone)
                            .withZoneSameInstant(utcZone).toLocalDateTime();
                    allRequiredTimesUtc.add(entryTimeUtc);
                    allRequiredTimesUtc.add(exitTimeUtc);

                    for (int rHours : rankingHoursOptions) {
                        java.time.LocalDateTime baseTimeUtc = date.atTime(eHour, 0).minusHours(rHours).atZone(userZone)
                                .withZoneSameInstant(utcZone).toLocalDateTime();
                        allRequiredTimesUtc.add(baseTimeUtc);
                    }
                }
            }

            sharedPriceMap = indexCalculatorService.getKlineService().getBulkPricesAtTimes(allRequiredTimesUtc);

            // 并行执行
            final Map<java.time.LocalDateTime, Map<String, Double>> pricesForTask = sharedPriceMap;
            List<Map<String, Object>> allResults = combinations.parallelStream()
                    .map(combo -> {
                        int eHour = combo[0];
                        int rHours = combo[1];
                        int tN = combo[2];
                        double amountPerCoin = totalAmount / tN;

                        com.binance.index.dto.BacktestResult backtestResult = indexCalculatorService
                                .runShortTopNBacktestApi(
                                        eHour, 0, amountPerCoin, days, rHours, holdHours, tN, timezone, true,
                                        pricesForTask);

                        Map<String, Object> res = new HashMap<>();
                        res.put("entryHour", eHour);
                        res.put("rankingHours", rHours);
                        res.put("topN", tN);
                        res.put("totalProfit", backtestResult.getTotalProfit());
                        res.put("dailyResults", backtestResult.getDailyResults());
                        return res;
                    })
                    .collect(java.util.stream.Collectors.toList());

            long endTime = System.currentTimeMillis();

            // ===== 在截取 topN 之前，先统计每天全部组合的赚/亏数量 =====
            Map<String, Map<String, Object>> dailyWinLoss = new HashMap<>();
            for (Map<String, Object> combo : allResults) {
                @SuppressWarnings("unchecked")
                List<com.binance.index.dto.BacktestDailyResult> dailyResults = (List<com.binance.index.dto.BacktestDailyResult>) combo
                        .get("dailyResults");
                if (dailyResults != null) {
                    for (com.binance.index.dto.BacktestDailyResult dr : dailyResults) {
                        dailyWinLoss.computeIfAbsent(dr.getDate(), k -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("win", 0);
                            m.put("lose", 0);
                            m.put("total", 0);
                            return m;
                        });
                        Map<String, Object> counts = dailyWinLoss.get(dr.getDate());
                        counts.put("total", (int) counts.get("total") + 1);
                        if (dr.getTotalProfit() >= 0) {
                            counts.put("win", (int) counts.get("win") + 1);
                        } else {
                            counts.put("lose", (int) counts.get("lose") + 1);
                        }
                    }
                }
            }
            // 计算胜率百分比
            for (Map<String, Object> counts : dailyWinLoss.values()) {
                int total = (int) counts.get("total");
                int win = (int) counts.get("win");
                counts.put("winRate", total > 0 ? Math.round(win * 10000.0 / total) / 100.0 : 0.0);
            }

            // 按 totalProfit 排序并限制返回条数
            allResults.sort((a, b) -> Double.compare(
                    ((Number) b.get("totalProfit")).doubleValue(),
                    ((Number) a.get("totalProfit")).doubleValue()));
            if (topNLimit > 0 && allResults.size() > topNLimit) {
                allResults = allResults.subList(0, topNLimit);
            }

            // ===== 分页处理：对每个组合的 dailyResults 进行分页 =====
            // 使用 dailyWinLoss 的全量日期（不受 topN 截取影响，与折线图一致）
            java.util.Set<String> allDates = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
            allDates.addAll(dailyWinLoss.keySet());

            List<String> sortedDates = new java.util.ArrayList<>(allDates);
            int totalDays = sortedDates.size();
            int totalPages = (int) Math.ceil((double) totalDays / pageSize);

            // 计算当前页的日期范围
            int startIdx = (page - 1) * pageSize;
            int endIdx = Math.min(startIdx + pageSize, totalDays);
            java.util.Set<String> currentPageDates = new java.util.HashSet<>(
                    sortedDates.subList(startIdx, endIdx));

            // 过滤每个组合的 dailyResults，只保留当前页的日期
            for (Map<String, Object> combo : allResults) {
                @SuppressWarnings("unchecked")
                List<com.binance.index.dto.BacktestDailyResult> dailyResults = (List<com.binance.index.dto.BacktestDailyResult>) combo
                        .get("dailyResults");
                if (dailyResults != null) {
                    List<com.binance.index.dto.BacktestDailyResult> pagedResults = dailyResults.stream()
                            .filter(dr -> currentPageDates.contains(dr.getDate()))
                            .collect(java.util.stream.Collectors.toList());
                    combo.put("dailyResults", pagedResults);
                }
            }

            response.put("success", true);
            response.put("combinations", allResults);
            response.put("dailyWinLoss", dailyWinLoss);
            response.put("timeTakenMs", endTime - startTime);
            // 分页元数据
            response.put("pagination", Map.of(
                    "page", page,
                    "pageSize", pageSize,
                    "totalDays", totalDays,
                    "totalPages", totalPages));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("每日策略优化失败", e);
            response.put("success", false);
            response.put("message", "执行失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 手动同步K线数据
     * 
     * @param days 同步最近多少天的数据
     */
    @PostMapping("/backtest/sync-data")
    public ResponseEntity<Map<String, Object>> syncBacktestData(@RequestParam(defaultValue = "30") int days) {
        log.info("收到手动同步数据请求: days={}", days);
        Map<String, Object> result = new HashMap<>();
        try {
            // 在后台线程执行，避免接口超时
            new Thread(() -> {
                try {
                    indexCalculatorService.syncKlineData(days);
                } catch (Exception e) {
                    log.error("后台数据同步失败", e);
                }
            }, "manual-sync-thread").start();

            result.put("success", true);
            result.put("message", "数据同步任务已在后台启动，请查看日志关注进度");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("启动数据同步失败", e);
            result.put("success", false);
            result.put("message", "启动同步失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 清空本地小时K线缓存数据
     */
    @PostMapping("/backtest/clear-cache")
    public ResponseEntity<Map<String, Object>> clearKlineCache() {
        log.info("收到清空 K 线缓存请求");
        Map<String, Object> result = new HashMap<>();
        try {
            if (klineService != null) {
                klineService.clearCache();
                result.put("success", true);
                result.put("message", "本地 K 线缓存已成功清空");
                return ResponseEntity.ok(result);
            } else {
                result.put("success", false);
                result.put("message", "KlineService 未初始化");
                return ResponseEntity.badRequest().body(result);
            }
        } catch (Exception e) {
            log.error("清空缓存失败", e);
            result.put("success", false);
            result.put("message", "清空缓存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 查询K线历史数据
     * 
     * 支持两种模式：
     * 1. 传入 symbol：查询指定币种的历史K线（可选 hours 限制条数）
     * 2. 传入 startTime + endTime：查询时间范围内所有币种的K线（用于数据检查）
     * 
     * @param symbol    币种名称（如 BTCUSDT），模式1必填
     * @param hours     限制返回最近多少小时的数据（模式1可选）
     * @param startTime 开始时间（UTC，格式：yyyy-MM-dd'T'HH:mm:ss），模式2必填
     * @param endTime   结束时间（UTC，格式同上），模式2必填
     */
    @GetMapping("/kline/history")
    public ResponseEntity<Map<String, Object>> getKlineHistory(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer hours,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {

        Map<String, Object> response = new HashMap<>();

        try {
            // 模式2: 时间范围查询所有币种
            if (startTime != null && endTime != null) {
                log.info("查询时间范围K线(北京时间): startTime={}, endTime={}", startTime, endTime);

                // 入参为北京时间，转换为 UTC
                java.time.ZoneId shanghaiZone = java.time.ZoneId.of("Asia/Shanghai");
                java.time.ZoneId utcZone = java.time.ZoneId.of("UTC");

                java.time.LocalDateTime startLocal = java.time.LocalDateTime.parse(startTime);
                java.time.LocalDateTime endLocal = java.time.LocalDateTime.parse(endTime);

                java.time.LocalDateTime start = startLocal.atZone(shanghaiZone).withZoneSameInstant(utcZone)
                        .toLocalDateTime();
                java.time.LocalDateTime end = endLocal.atZone(shanghaiZone).withZoneSameInstant(utcZone)
                        .toLocalDateTime();

                log.info("转换为UTC时间: start={}, end={}", start, end);

                // 使用原生查询获取时间范围内的所有数据
                List<Object[]> partialData = klineService.getHourlyKlineRepository()
                        .findAllPartialByOpenTimeBetween(start, end);

                // 按时间点分组
                Map<String, Map<String, Double>> groupedData = new java.util.LinkedHashMap<>();
                for (Object[] row : partialData) {
                    String sym = (String) row[0];
                    java.time.LocalDateTime time = (java.time.LocalDateTime) row[1];
                    Double price = (Double) row[2];
                    String timeKey = time.toString();
                    groupedData.computeIfAbsent(timeKey, t -> new java.util.TreeMap<>()).put(sym, price);
                }

                // 统计每个时间点的币种数量
                List<Map<String, Object>> timeSlots = new java.util.ArrayList<>();
                for (Map.Entry<String, Map<String, Double>> entry : groupedData.entrySet()) {
                    Map<String, Object> slot = new java.util.HashMap<>();
                    slot.put("time", entry.getKey());
                    // 添加东八区时间字段
                    java.time.LocalDateTime utcTime = java.time.LocalDateTime.parse(entry.getKey());
                    java.time.ZonedDateTime cnTime = utcTime.atZone(java.time.ZoneId.of("UTC"))
                            .withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"));
                    slot.put("timeCN", cnTime.toLocalDateTime().toString());
                    slot.put("symbolCount", entry.getValue().size());
                    slot.put("prices", entry.getValue());
                    timeSlots.add(slot);
                }

                response.put("success", true);
                response.put("mode", "timeRange");
                response.put("startTime", startTime);
                response.put("endTime", endTime);
                response.put("totalRecords", partialData.size());
                response.put("timeSlotCount", timeSlots.size());
                response.put("data", timeSlots);

                log.info("时间范围K线查询完成: {} 到 {}, {} 条记录, {} 个时间点",
                        startTime, endTime, partialData.size(), timeSlots.size());
                return ResponseEntity.ok(response);
            }

            // 模式1: 单币种查询
            if (symbol == null || symbol.isEmpty()) {
                response.put("success", false);
                response.put("message", "请传入 symbol 参数，或同时传入 startTime 和 endTime 参数");
                return ResponseEntity.badRequest().body(response);
            }

            log.info("查询K线历史: symbol={}, hours={}", symbol, hours);
            String normalizedSymbol = symbol.toUpperCase();

            List<com.binance.index.entity.HourlyKline> klines;

            if (hours != null && hours > 0) {
                org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0,
                        hours);
                klines = klineService.getHourlyKlineRepository()
                        .findBySymbolOrderByOpenTimeDesc(normalizedSymbol, pageable);
            } else {
                klines = klineService.getHourlyKlineRepository()
                        .findBySymbolOrderByOpenTimeDesc(normalizedSymbol);
            }

            List<Map<String, Object>> data = klines.stream().map(k -> {
                Map<String, Object> item = new HashMap<>();
                item.put("openTime", k.getOpenTime().toString());
                // 添加东八区时间字段
                java.time.ZonedDateTime cnTime = k.getOpenTime().atZone(java.time.ZoneId.of("UTC"))
                        .withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"));
                item.put("openTimeCN", cnTime.toLocalDateTime().toString());
                item.put("openPrice", k.getOpenPrice());
                item.put("highPrice", k.getHighPrice());
                item.put("lowPrice", k.getLowPrice());
                item.put("closePrice", k.getClosePrice());
                item.put("volume", k.getVolume());
                return item;
            }).collect(Collectors.toList());

            response.put("success", true);
            response.put("mode", "symbol");
            response.put("symbol", normalizedSymbol);
            response.put("count", data.size());
            response.put("data", data);

            if (!klines.isEmpty()) {
                response.put("latestTime", klines.get(0).getOpenTime().toString());
                response.put("earliestTime", klines.get(klines.size() - 1).getOpenTime().toString());
            }
            log.info("K线历史查询完成: symbol={}, 返回 {} 条记录", normalizedSymbol, data.size());
            return ResponseEntity.ok(response);

        } catch (java.time.format.DateTimeParseException e) {
            log.error("时间格式错误", e);
            response.put("success", false);
            response.put("message", "时间格式错误，请使用 yyyy-MM-dd'T'HH:mm:ss 格式，如 2026-01-30T05:00:00");
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("查询K线历史失败", e);
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 实时持仓监控
     * 监控每个整点小时做空涨幅榜的盈亏情况
     *
     * @param rankingHours  涨幅榜周期（默认24小时）
     * @param topN          做空前N名（默认10）
     * @param hourlyAmount  每小时总金额（默认1000U）
     * @param monitorHours  监控小时数（默认24）
     * @param timezone      时区（默认 Asia/Shanghai）
     * @param backtrackTime 回溯时间（可选，格式：yyyy-MM-dd HH:mm:ss），指定后以该时间作为"当前时间"计算
     * @return 监控结果
     */
    @GetMapping("/live-monitor")
    public ResponseEntity<Map<String, Object>> liveMonitor(
            @RequestParam(defaultValue = "24") int rankingHours,
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam(defaultValue = "1000") double hourlyAmount,
            @RequestParam(defaultValue = "24") int monitorHours,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone,
            @RequestParam(required = false) String backtrackTime) {

        log.info("========== 开始调用 /live-monitor 接口 ==========");
        log.info("参数: rankingHours={}, topN={}, hourlyAmount={}, monitorHours={}, timezone={}, backtrackTime={}",
                rankingHours, topN, hourlyAmount, monitorHours, timezone, backtrackTime);

        try {
            Map<String, Object> result = indexCalculatorService.liveMonitor(
                    rankingHours, topN, hourlyAmount, monitorHours, timezone, backtrackTime);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("实时监控失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "监控失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 逐小时盈亏追踪
     * 
     * 核心逻辑：
     * 1. entryTime 只用于确定做空的币种列表和入场价格
     * 2. 追踪范围：从 (当前整点 - monitorHours) 到 (当前整点) + 最新5分钟K线
     * 3. 所有快照的盈亏都相对于 entryTime 的入场价格计算
     */
    @GetMapping("/live-monitor/hourly-tracking")
    public ResponseEntity<Map<String, Object>> getHourlyTracking(
            @RequestParam String entryTime,
            @RequestParam(required = false) String symbols, // 手动选币模式
            @RequestParam(defaultValue = "24") int rankingHours,
            @RequestParam(defaultValue = "5") int topN,
            @RequestParam(defaultValue = "1000") double totalAmount,
            @RequestParam(defaultValue = "24") int monitorHours,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {

        log.info("========== 开始调用 /live-monitor/hourly-tracking 接口 ==========");
        log.info("参数: entryTime={}, symbols={}, rankingHours={}, topN={}, totalAmount={}, monitorHours={}, timezone={}",
                entryTime, symbols, rankingHours, topN, totalAmount, monitorHours, timezone);

        try {
            // 解析symbols参数（如果有）
            List<String> symbolList = null;
            if (symbols != null && !symbols.trim().isEmpty()) {
                symbolList = Arrays.stream(symbols.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }

            Map<String, Object> result = indexCalculatorService.getHourlyTracking(
                    entryTime, symbolList, rankingHours, topN, totalAmount, monitorHours, timezone);

            Map<String, Object> response = new HashMap<>();
            if (result.containsKey("error")) {
                response.put("success", false);
                response.put("message", result.get("error"));
            } else {
                response.put("success", true);
                response.put("data", result);
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("逐小时追踪失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "追踪失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 价格指数图 - 获取指定入场时间点的详细价格指数数据
     * 
     * 核心逻辑：
     * 1. entryTime 用于确定涨幅榜 Top N 币种
     * 2. 以入场时间点的价格为基准 (= 100)
     * 3. 返回入场前后各 lookbackHours 小时的价格指数数据
     * 4. 支持不同颗粒度 (5/15/30/60 分钟)
     * 
     * @param entryTime     入场时间
     * @param rankingHours  涨幅榜周期
     * @param topN          做空前N名
     * @param granularity   颗粒度（分钟），可选：5/15/30/60
     * @param lookbackHours 前后各查看多少小时
     * @param timezone      时区
     * @return 价格指数数据
     */
    @GetMapping("/live-monitor/price-index")
    public ResponseEntity<Map<String, Object>> getPriceIndex(
            @RequestParam String entryTime,
            @RequestParam(required = false) String symbols, // 手动选币模式
            @RequestParam(defaultValue = "24") int rankingHours,
            @RequestParam(defaultValue = "10") int topN,
            @RequestParam(defaultValue = "60") int granularity,
            @RequestParam(defaultValue = "24") int lookbackHours,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {

        log.info("========== 开始调用 /live-monitor/price-index 接口 ==========");
        log.info(
                "参数: entryTime={}, symbols={}, rankingHours={}, topN={}, granularity={}分钟, lookbackHours={}, timezone={}",
                entryTime, symbols, rankingHours, topN, granularity, lookbackHours, timezone);

        try {
            // 解析symbols参数（如果有）
            List<String> symbolList = null;
            if (symbols != null && !symbols.trim().isEmpty()) {
                symbolList = Arrays.stream(symbols.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }

            Map<String, Object> result = indexCalculatorService.getPriceIndexData(
                    entryTime, symbolList, rankingHours, topN, granularity, lookbackHours, timezone);

            Map<String, Object> response = new HashMap<>();
            if (result.containsKey("error")) {
                response.put("success", false);
                response.put("message", result.get("error"));
            } else {
                response.put("success", true);
                response.put("data", result);
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("价格指数获取失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 获取所有交易对及其24小时涨跌幅
     * 用于前端币种选择器（带5分钟缓存）
     */
    @GetMapping("/symbols/tickers")
    public ResponseEntity<Map<String, Object>> getAllSymbolTickers() {
        log.info("========== 开始调用 /symbols/tickers 接口 ==========");

        try {
            List<com.binance.index.dto.TickerData> tickers = binanceApiService.getAll24hTickers();

            // 转换为前端需要的格式
            List<Map<String, Object>> symbolList = tickers.stream()
                    .map(ticker -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("symbol", ticker.getSymbol());
                        item.put("priceChangePercent", ticker.getPriceChangePercent());
                        item.put("lastPrice", ticker.getLastPrice());
                        return item;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("symbols", symbolList);
            response.put("count", symbolList.size());

            log.info("成功返回 {} 个币种的ticker数据", symbolList.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取币种ticker数据失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 实时持仓监控 - 手动选币模式
     * 
     * @param symbols       用户选择的币种列表（逗号分隔，如 "BTCUSDT,ETHUSDT,SOLUSDT"）
     * @param hourlyAmount  每小时总金额
     * @param monitorHours  监控小时数
     * @param timezone      时区
     * @param backtrackTime 回溯时间（可选）
     */
    @GetMapping("/live-monitor/manual")
    public ResponseEntity<Map<String, Object>> liveMonitorManual(
            @RequestParam String symbols,
            @RequestParam(defaultValue = "1000") double hourlyAmount,
            @RequestParam(defaultValue = "24") int monitorHours,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone,
            @RequestParam(required = false) String backtrackTime) {

        log.info("========== 开始调用 /live-monitor/manual 接口 ==========");
        log.info("选择币种: {}", symbols);

        try {
            // 解析币种列表
            List<String> symbolList = Arrays.stream(symbols.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            if (symbolList.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "请至少选择一个币种");
                return ResponseEntity.badRequest().body(response);
            }

            Map<String, Object> result = indexCalculatorService.liveMonitorManual(
                    symbolList, hourlyAmount, monitorHours, timezone, backtrackTime);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("手动选币监控失败", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "监控失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 保存实时监控选择的币种
     */
    @PostMapping("/live-monitor/selected-symbols")
    public ResponseEntity<Map<String, Object>> saveLiveMonitorSymbols(
            @RequestBody Map<String, Object> request) {
        log.info("========== 开始调用 /live-monitor/selected-symbols (POST) 接口 ==========");

        Map<String, Object> response = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<String> symbols = (List<String>) request.get("symbols");

            if (symbols == null) {
                response.put("success", false);
                response.put("message", "symbols参数不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            indexCalculatorService.saveLiveMonitorSymbols(symbols);

            response.put("success", true);
            response.put("message", "保存成功");
            response.put("count", symbols.size());
            log.info("保存实时监控币种成功，共{}个", symbols.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("保存实时监控币种失败", e);
            response.put("success", false);
            response.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 获取实时监控选择的币种
     */
    @GetMapping("/live-monitor/selected-symbols")
    public ResponseEntity<Map<String, Object>> getLiveMonitorSymbols() {
        log.info("========== 开始调用 /live-monitor/selected-symbols (GET) 接口 ==========");

        Map<String, Object> response = new HashMap<>();
        try {
            List<String> symbols = indexCalculatorService.getLiveMonitorSymbols();

            response.put("success", true);
            response.put("symbols", symbols);
            response.put("count", symbols.size());
            log.info("获取实时监控币种成功，共{}个", symbols.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取实时监控币种失败", e);
            response.put("success", false);
            response.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
