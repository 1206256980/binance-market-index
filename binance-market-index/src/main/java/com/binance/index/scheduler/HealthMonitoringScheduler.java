package com.binance.index.scheduler;

import com.binance.index.service.EmailNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 健康监控调度器
 * 负责监控 JVM 内存等系统指标，并在异常时发送预警邮件
 */
@Component
@Slf4j
public class HealthMonitoringScheduler {

    private final EmailNotificationService emailNotificationService;

    @Value("${monitoring.memory.threshold:0.9}")
    private double memoryThreshold;

    @Value("${monitoring.memory.alert-interval-minutes:30}")
    private int alertIntervalMinutes;

    private LocalDateTime lastAlertTime;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public HealthMonitoringScheduler(EmailNotificationService emailNotificationService) {
        this.emailNotificationService = emailNotificationService;
    }

    /**
     * 每分钟检查一次 JVM 内存使用情况
     */
    // @Scheduled(fixedDelay = 60000)
    // public void monitorMemory() {
    // Runtime runtime = Runtime.getRuntime();
    // long maxMemory = runtime.maxMemory();
    // long totalMemory = runtime.totalMemory();
    // long freeMemory = runtime.freeMemory();
    // long usedMemory = totalMemory - freeMemory;

    // double usageRatio = (double) usedMemory / maxMemory;

    // if (log.isDebugEnabled()) {
    // log.debug("[JVM-MONITOR] Heap Usage: {} / {} ({})",
    // formatSize(usedMemory), formatSize(maxMemory), String.format("%.2f%%",
    // usageRatio * 100));
    // }

    // if (usageRatio >= memoryThreshold) {
    // handleMemoryAlert(usedMemory, maxMemory, usageRatio);
    // }
    // }

    private void handleMemoryAlert(long used, long max, double ratio) {
        LocalDateTime now = LocalDateTime.now();

        // 防骚扰检查：一定时间内不重复发送
        if (lastAlertTime != null && now.isBefore(lastAlertTime.plusMinutes(alertIntervalMinutes))) {
            return;
        }

        String usagePercent = String.format("%.2f%%", ratio * 100);
        log.warn("[ALERT] JVM Memory Usage is high: {}! Threshold: {}", usagePercent, memoryThreshold);

        String subject = "JVM 内存水位预警 (" + usagePercent + ")";
        StringBuilder content = new StringBuilder();
        content.append("🚨 币安指数监控 - JVM 内存水位预警\n\n");
        content.append("当前时间: ").append(now.format(FORMATTER)).append("\n");
        content.append("当前使用率: ").append(usagePercent).append("\n");
        content.append("已用内存: ").append(formatSize(used)).append("\n");
        content.append("最大堆内存: ").append(formatSize(max)).append("\n");
        content.append("预警阈值: ").append(String.format("%.0f%%", memoryThreshold * 100)).append("\n\n");

        content.append("⚠️ 服务可能即将发生 OOM (内存溢出) 崩溃！\n");
        content.append("建议采取以下措施:\n");
        content.append("1. 排查最近是否有大范围的数据计算任务（如单边上行回补）。\n");
        content.append("2. 检查应用日志，看是否有频繁的 Full GC。\n");
        content.append("3. 如果业务量确实很大，请调高 Docker 容器内存并修改 JVM (-Xmx) 参数。\n");
        content.append("4. 为保险起见，建议在低峰期重启服务释放内存空间。\n");

        emailNotificationService.sendNotification(subject, content.toString());
        lastAlertTime = now;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.2f %s", bytes / Math.pow(1024, exp), pre);
    }
}
