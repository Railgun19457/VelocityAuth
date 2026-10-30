package io.github.railgun19457.velocityauth.mail;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 内置 SMTP 发送器（Jakarta Mail / angus-mail）。
 * <p>
 * 所有发送都在 {@link AsyncExecutor} 中执行，不阻塞事件循环。
 */
public class SmtpSender implements MailSender {

    private final Supplier<VelocityAuthConfig> config;
    private final AsyncExecutor async;
    private final PluginLogger log;

    public SmtpSender(Supplier<VelocityAuthConfig> config, AsyncExecutor async, PluginLogger log) {
        this.config = config;
        this.async = async;
        this.log = log;
    }

    @Override
    public boolean available() {
        VelocityAuthConfig.Smtp smtp = config.get().smtp();
        return smtp.enabled() && !smtp.host().isBlank();
    }

    @Override
    public CompletableFuture<Boolean> send(String to, String subject, String text, String html) {
        if (!available()) {
            return CompletableFuture.completedFuture(false);
        }
        return async.supply(() -> sendBlocking(to, subject, text, html));
    }

    private boolean sendBlocking(String to, String subject, String text, String html) {
        VelocityAuthConfig.Smtp smtp = config.get().smtp();
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtp.host());
            props.put("mail.smtp.port", Integer.toString(smtp.port()));
            props.put("mail.smtp.ssl.enable", Boolean.toString(smtp.ssl()));
            // ssl=false 时尝试 STARTTLS（常见于 587 端口），避免明文提交凭据
            props.put("mail.smtp.starttls.enable", Boolean.toString(!smtp.ssl()));
            props.put("mail.smtp.auth", Boolean.toString(!smtp.username().isBlank()));
            int timeout = Math.max(1, smtp.timeoutSeconds()) * 1000;
            props.put("mail.smtp.connectiontimeout", Integer.toString(timeout));
            props.put("mail.smtp.timeout", Integer.toString(timeout));
            props.put("mail.smtp.writetimeout", Integer.toString(timeout));

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(smtp.username(), smtp.password());
                }
            });

            MimeMessage message = new MimeMessage(session);
            InternetAddress[] fromAddresses = InternetAddress.parse(smtp.from(), false);
            if (fromAddresses.length == 0) {
                throw new AddressException("smtp.from 未配置");
            }
            message.setFrom(fromAddresses[0]);
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            if (html == null || html.isBlank()) {
                message.setText(text == null ? "" : text, StandardCharsets.UTF_8.name());
            } else {
                // multipart/alternative：优先显示 HTML，纯文本作为兜底
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(text == null ? "" : text, StandardCharsets.UTF_8.name());
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(html, "text/html; charset=UTF-8");
                MimeMultipart multipart = new MimeMultipart("alternative");
                multipart.addBodyPart(textPart);
                multipart.addBodyPart(htmlPart);
                message.setContent(multipart);
            }

            Transport.send(message);
            return true;
        } catch (Exception e) {
            // 不记录正文（可能含验证码），只记录异常类型/消息与收件域名
            log.warn("发送邮件失败（收件人域名 {}）：{} - {}",
                    domainOf(to), e.getClass().getSimpleName(), e.getMessage());
            log.debug("邮件发送异常详情", e);
            log.debug("邮件发送异常详情", e);
            return false;
        }
    }

    private static String domainOf(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at < 0 ? "(unknown)" : email.substring(at + 1);
    }
}
