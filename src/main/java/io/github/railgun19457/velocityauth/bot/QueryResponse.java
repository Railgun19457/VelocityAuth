package io.github.railgun19457.velocityauth.bot;

import java.util.Map;

/**
 * 一条 {@code query.*} 请求的处理结果：要么是成功载荷（发回 {@code <type>.result}），
 * 要么是错误码（发回密封 {@code error} 帧）。
 */
public record QueryResponse(Map<String, Object> payload, String errorCode, String errorMessage) {

    public static QueryResponse ok(Map<String, Object> payload) {
        return new QueryResponse(payload, null, null);
    }

    public static QueryResponse error(String code, String message) {
        return new QueryResponse(null, code, message);
    }

    public boolean isError() {
        return errorCode != null;
    }
}
