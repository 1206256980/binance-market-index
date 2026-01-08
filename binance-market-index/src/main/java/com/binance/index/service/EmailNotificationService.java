package com.binance.index.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 邮件通知服务
 * 用于在采集失败等异常情况下发送邮件报警
 */
@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaMailSender mailSender;

    @Value("${notification.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Value("${notification.email.to:}")
    private String toEmail;

    @Value("${notification.email.subject-prefix:[币安指数监控]}")
    private String subjectPrefix;

    public EmailNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * 发送采集失败通知
     *
     * @param errorMessage 错误信息
     * @param exception    异常对象（可为null）
     */
    public void sendCollectionFailureNotification(String errorMessage, Exception exception) {
        if (!emailEnabled) {
            log.debug("邮件通知未启用，跳过发送");
            return;
        }

        if (fromEmail.isEmpty() || toEmail.isEmpty()) {
            log.warn("邮件配置不完整（发件人或收件人为空），跳过发送");
            return;
        }

        try {
            String subject = subjectPrefix + " 数据采集失败报警";
            StringBuilder content = new StringBuilder();
            content.append("⚠️ 币安指数数据采集失败报警\n\n");
            content.append("时间(北京): ").append(LocalDateTime.now(BEIJING_ZONE).format(FORMATTER)).append("\n");
            content.append("错误信息: ").append(errorMessage).append("\n\n");

            if (exception != null) {
                content.append("异常类型: ").append(exception.getClass().getName()).append("\n");
                content.append("异常详情: ").append(exception.getMessage()).append("\n\n");

                // 添加完整堆栈信息
                StackTraceElement[] stackTrace = exception.getStackTrace();
                if (stackTrace.length > 0) {
                    content.append("完整堆栈追踪:\n");
                    for (StackTraceElement element : stackTrace) {
                        content.append("  at ").append(element.toString()).append("\n");
                    }
                }

                // 如果有 cause，也打印出来
                Throwable cause = exception.getCause();
                if (cause != null) {
                    content.append("\nCaused by: ").append(cause.getClass().getName())
                            .append(": ").append(cause.getMessage()).append("\n");
                    for (StackTraceElement element : cause.getStackTrace()) {
                        content.append("  at ").append(element.toString()).append("\n");
                    }
                }
            }

            content.append("\n----------------------------------------\n");
            content.append("⚠️ 后续采集已暂停，请检查并修复问题后：\n");
            content.append("1. 重启服务，或\n");
            content.append("2. 调用 /rebackfill 接口重新回补数据\n");

            sendEmail(subject, content.toString());
            log.info("采集失败通知邮件已发送至: {}", toEmail);

        } catch (Exception e) {
            log.error("发送采集失败通知邮件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送限流警告通知
     *
     * @param rateLimitInfo 限流信息
     */
    public void sendRateLimitNotification(String rateLimitInfo) {
        if (!emailEnabled) {
            log.debug("邮件通知未启用，跳过发送");
            return;
        }

        if (fromEmail.isEmpty() || toEmail.isEmpty()) {
            log.warn("邮件配置不完整，跳过发送");
            return;
        }

        try {
            String subject = subjectPrefix + " API限流警告";
            StringBuilder content = new StringBuilder();
            content.append("🚨 币安API限流警告\n\n");
            content.append("时间(北京): ").append(LocalDateTime.now(BEIJING_ZONE).format(FORMATTER)).append("\n");
            content.append("限流信息: ").append(rateLimitInfo).append("\n\n");
            content.append("⚠️ IP可能已被币安临时封禁！\n");
            content.append("建议: 检查请求频率，或更换IP节点\n");

            sendEmail(subject, content.toString());
            log.info("限流警告通知邮件已发送至: {}", toEmail);

        } catch (Exception e) {
            log.error("发送限流警告通知邮件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送自定义通知
     *
     * @param subject 邮件主题（不含前缀）
     * @param content 邮件内容
     */
    public void sendNotification(String subject, String content) {
        if (!emailEnabled) {
            log.debug("邮件通知未启用，跳过发送");
            return;
        }

        if (fromEmail.isEmpty() || toEmail.isEmpty()) {
            log.warn("邮件配置不完整，跳过发送");
            return;
        }

        try {
            sendEmail(subjectPrefix + " " + subject, content);
            log.info("通知邮件已发送至: {}", toEmail);
        } catch (Exception e) {
            log.error("发送通知邮件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送邮件的核心方法（使用MimeMessage支持UTF-8编码）
     */
    private void sendEmail(String subject, String content) throws MessagingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");

        helper.setFrom(fromEmail);
        helper.setTo(toEmail);
        helper.setSubject(subject);
        helper.setText(content);

        mailSender.send(mimeMessage);
    }

    /**
     * 检查邮件服务是否可用
     */
    public boolean isEmailServiceAvailable() {
        return emailEnabled && !fromEmail.isEmpty() && !toEmail.isEmpty();
    }
}
