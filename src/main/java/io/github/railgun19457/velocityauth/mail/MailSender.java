package io.github.railgun19457.velocityauth.mail;

import java.util.concurrent.CompletableFuture;

/**
 * 邮件发送抽象：便于替换实现与单元测试。
 */
public interface MailSender {

    /** 邮件服务是否可用。 */
    boolean available();

    /**
     * 异步发送一封邮件；返回是否成功。
     *
     * @param text 纯文本正文（不支持 HTML 的客户端显示这份）
     * @param html HTML 正文；为空时只发纯文本
     */
    CompletableFuture<Boolean> send(String to, String subject, String text, String html);
}
