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

import java.util.ArrayList;
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

    // 限制 uptrend-distribution 接口并发为1的信号量
    private final Semaphore uptrendSemaphore = new Semaphore(1);

    public IndexController(IndexCalculatorService indexCalculatorService,
            com.binance.index.scheduler.DataCollectorScheduler dataCollectorScheduler,
            com.binance.index.service.KlineService klineService) {
        this.indexCalculatorService = indexCalculatorService;
        this.dataCollectorScheduler = dataCollectorScheduler;
        this.klineService = klineService;
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
            @RequestParam(defaultValue = "false") boolean useApi,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {
        log.info("------------------------- 开始调用 /backtest/short-top10 接口 (useApi={}) -------------------------",
                useApi);
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
            if (useApi) {
                // 使用币安API获取历史数据（支持更长时间范围）
                result = indexCalculatorService.runShortTopNBacktestApi(
                        entryHour, entryMinute, amountPerCoin, days, rankingHours, holdHours, topN, timezone);
            } else {
                // 使用本地数据库（更快但数据有限）
                result = indexCalculatorService.runShortTop10Backtest(
                        entryHour, entryMinute, amountPerCoin, days, rankingHours, holdHours, topN, timezone);
            }

            response.put("success", true);
            response.put("params", Map.of(
                    "entryHour", entryHour,
                    "entryMinute", entryMinute,
                    "totalAmount", totalAmount,
                    "amountPerCoin", amountPerCoin,
                    "days", days,
                    "rankingHours", rankingHours,
                    "timezone", timezone));
            response.put("summary", Map.of(
                    "totalDays", result.getTotalDays(),
                    "validDays", result.getValidDays(),
                    "totalTrades", result.getTotalTrades(),
                    "winTrades", result.getWinTrades(),
                    "loseTrades", result.getLoseTrades(),
                    "winRate", result.getWinRate(),
                    "totalProfit", result.getTotalProfit()));
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
            @RequestParam(defaultValue = "false") boolean useApi) {
        log.info("------------------------- 开始调用 /backtest/optimize 接口 (useApi={}) -------------------------", useApi);
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
            int[] rankingHoursOptions = { 24, 48, 72, 168 };
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
            List<String> finalSymbols = null;
            if (useApi) {
                log.info("🚀 优化器检测到使用 API，开始执行全局预加载...");
                long startGlobalPreload = System.currentTimeMillis();

                // 1. 获取所有币种
                finalSymbols = indexCalculatorService.getBinanceApiService().getAllUsdtSymbols();

                // 2. 找到所有组合中的最大回退范围和最大持仓时间
                int maxRankingHours = java.util.Arrays.stream(rankingHoursOptions).max().orElse(24);
                int maxHoldHours = java.util.Arrays.stream(holdHoursOptions).max().orElse(24);

                // 3. 计算全局预加载范围
                java.time.ZoneId userZone = java.time.ZoneId.of(timezone);
                java.time.LocalDate today = java.time.LocalDate.now(userZone);
                java.time.LocalDate endDate = today.minusDays(1);
                java.time.LocalDate startDate = endDate.minusDays(days - 1);

                // 取最早可能的入场时间点
                int minEntryHour = java.util.Arrays.stream(entryHourOptions).min().orElse(0);
                // 取最晚可能的入场时间点
                int maxEntryHour = java.util.Arrays.stream(entryHourOptions).max().orElse(23);

                java.time.LocalDateTime globalPreloadStart = startDate.atTime(minEntryHour, 0)
                        .minusHours(maxRankingHours + 1);
                java.time.LocalDateTime globalPreloadEnd = endDate.atTime(maxEntryHour, 0).plusHours(maxHoldHours);

                log.info("📦 执行全局预加载: {} 至 {}", globalPreloadStart, globalPreloadEnd);
                indexCalculatorService.getKlineService().preloadKlines(globalPreloadStart, globalPreloadEnd,
                        finalSymbols);
                log.info("⏱️ 全局预加载完成，共耗时: {}ms", (System.currentTimeMillis() - startGlobalPreload));
            }
            // --- 优化结束 ---

            // 使用并行流执行回测
            final List<String> symbolsForTask = finalSymbols; // effectively final
            List<Map<String, Object>> allResults = combinations.parallelStream()
                    .map(combo -> {
                        int rHours = combo[0];
                        int tN = combo[1];
                        int eHour = combo[2];
                        int hHours = combo[3];
                        double amountPerCoin = totalAmount / tN;

                        com.binance.index.dto.BacktestResult backtestResult;
                        if (useApi) {
                            // 使用性能优化版，传入预加载好的币种并跳过内部 preload
                            backtestResult = indexCalculatorService.runShortTopNBacktestApi(
                                    eHour, 0, amountPerCoin, days, rHours, hHours, tN, timezone, symbolsForTask, true);
                        } else {
                            backtestResult = indexCalculatorService.runShortTop10Backtest(
                                    eHour, 0, amountPerCoin, days, rHours, hHours, tN, timezone);
                        }

                        Map<String, Object> res = new HashMap<>();
                        res.put("rankingHours", rHours);
                        res.put("topN", tN);
                        res.put("entryHour", eHour);
                        res.put("holdHours", hHours);
                        res.put("totalProfit", backtestResult.getTotalProfit());
                        res.put("winRate", backtestResult.getWinRate());
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

            List<Map<String, Object>> sortedResults = allResults.stream()
                    .collect(java.util.stream.Collectors.toList());

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
}
