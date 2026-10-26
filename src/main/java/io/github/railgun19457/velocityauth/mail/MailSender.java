package io.github.railgun19457.velocityauth.mail;

import java.util.concurrent.CompletableFuture;

/**
 * 邮件发送抽象：便于替换实现与单元测试。
 */
public interface MailSender {

    /** 邮件服务是否可用。 */
    boolean available();

    /** 异步发送一封纯文本邮件；返回是否成功。 */
    CompletableFuture<Boolean> send(String to, String subject, String body);
}
