package com.binance.index.scheduler;

import com.binance.index.service.EmailNotificationService;
import com.binance.index.service.IndexCalculatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DataCollectorScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataCollectorScheduler.class);

    private final IndexCalculatorService indexCalculatorService;
    private final EmailNotificationService emailNotificationService;

    @Value("${index.backfill.days}")
    private int backfillDays;

    @Value("${binance.api.backfill-concurrency:5}")
    private int backfillConcurrency;

    private volatile boolean isBackfillComplete = false;
    private volatile boolean hasCollectionError = false; // 采集出错后暂停后续采集

    public DataCollectorScheduler(IndexCalculatorService indexCalculatorService,
                                   EmailNotificationService emailNotificationService) {
        this.indexCalculatorService = indexCalculatorService;
        this.emailNotificationService = emailNotificationService;
    }

    /**
     * 应用启动后执行历史数据回补（使用 V2 优化版）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("应用启动完成，开始初始化...");

        // 异步执行初始化和回补，不阻塞应用启动
        new Thread(() -> {
            try {
                // 1. 先清理可能存在的重复数据（唯一约束生效前的历史遗留）
                indexCalculatorService.cleanupDuplicateData();

                // 2. 设置回补状态
                indexCalculatorService.setBackfillInProgress(true);

                // 3. 使用 V2 优化版回补（两阶段并发）
                log.info("开始 V2 优化版回补历史数据...");
                indexCalculatorService.backfillHistoricalDataV2(backfillDays, backfillConcurrency);

                // V2 版本不需要 flushPendingData，因为每阶段结束后已计算指数

                // 4. 清除回补状态
                indexCalculatorService.setBackfillInProgress(false);
                isBackfillComplete = true;
                log.info("V2 历史数据回补完成");
            } catch (Exception e) {
                log.error("历史数据回补失败: {}", e.getMessage(), e);
                indexCalculatorService.setBackfillInProgress(false);
            }
        }, "backfill-v2-thread").start();
    }

    /**
     * 手动触发重新回补数据（通过API调用）
     * 重置状态并重新执行回补流程，回补成功后才恢复采集
     */
    public void rebackfill() {
        log.info("手动触发重新回补数据...");

        // 先标记为未完成，暂停实时采集
        isBackfillComplete = false;

        // 异步执行回补，不阻塞请求
        new Thread(() -> {
            try {
                // 1. 先清理可能存在的重复数据
                indexCalculatorService.cleanupDuplicateData();

                // 2. 设置回补状态
                indexCalculatorService.setBackfillInProgress(true);

                // 3. 使用 V2 优化版回补
                log.info("开始 V2 优化版回补历史数据...");
                indexCalculatorService.backfillHistoricalDataV2(backfillDays, backfillConcurrency);

                // 4. 回补成功，清除回补状态和错误标志
                indexCalculatorService.setBackfillInProgress(false);
                hasCollectionError = false; // 回补成功后才重置错误标志
                isBackfillComplete = true;
                log.info("V2 历史数据回补完成，采集已恢复");
            } catch (Exception e) {
                log.error("历史数据回补失败，采集仍处于暂停状态: {}", e.getMessage(), e);
                indexCalculatorService.setBackfillInProgress(false);
                // 回补失败时不重置 hasCollectionError，保持暂停状态
            }
        }, "rebackfill-thread").start();
    }

    /**
     * 每5分钟采集一次数据
     * cron: 秒 分 时 日 月 周
     * 20 0/5 * * * * 表示每5分钟的第20秒执行（给币安API更多时间更新K线数据）
     */
    @Scheduled(cron = "20 0/5 * * * *")
    public void collectData() {
        if (!isBackfillComplete) {
            log.debug("历史数据回补尚未完成，跳过本次采集");
            return;
        }

        // 如果之前采集出错，暂停后续采集，避免数据缺漏
        if (hasCollectionError) {
            log.warn("实时采集已暂停（之前发生错误），请检查并修复问题后重启服务");
            return;
        }

        try {
            log.info("------------------------- 开始实时采集 -------------------------");
            indexCalculatorService.calculateAndSaveCurrentIndex();
        } catch (Exception e) {
            log.error("数据采集失败，后续采集已暂停: {}", e.getMessage(), e);
            hasCollectionError = true; // 标记错误，暂停后续采集
            
            // 发送邮件通知
            emailNotificationService.sendCollectionFailureNotification(e.getMessage(), e);
        }
    }

    // 基准价格永不自动刷新，仅在首次启动时通过回补历史数据设定
    // 如需更改回补天数，修改配置 index.backfill.days (默认7天)
}
