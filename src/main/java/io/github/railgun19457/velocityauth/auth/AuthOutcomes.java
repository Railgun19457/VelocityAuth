package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.storage.model.Account;

/**
 * 认证流程的结果类型集合。
 */
public final class AuthOutcomes {

    private AuthOutcomes() {
    }

    public enum LoginStatus {
        SUCCESS,
        WRONG_PASSWORD,
        LOCKED,
        /** 密码正确，但账号未满足「注册后必须绑定」的要求。 */
        BINDING_REQUIRED,
        RATE_LIMITED,
        ERROR
    }

    public record Login(LoginStatus status, Account account) {
        public static Login success(Account account) {
            return new Login(LoginStatus.SUCCESS, account);
        }

        public static Login failure(LoginStatus status) {
            return new Login(status, null);
        }

        /** 需要先完成绑定：带上账号，便于提示并放行 `/bind`。 */
        public static Login bindingRequired(Account account) {
            return new Login(LoginStatus.BINDING_REQUIRED, account);
        }
    }

    public enum RegisterStatus {
        SUCCESS,
        NAME_TAKEN,
        NAME_INVALID,
        PASSWORD_TOO_SHORT,
        PASSWORD_TOO_LONG,
        PASSWORD_TOO_LONG_BYTES,
        PASSWORD_MISMATCH,
        RATE_LIMITED,
        EMAIL_INVALID,
        DOMAIN_NOT_ALLOWED,
        EMAIL_LIMIT,
        QQ_LIMIT,
        VERIFICATION_CANCELLED,
        VERIFICATION_EXPIRED,
        VERIFICATION_FAILED,
        ERROR
    }

    public record Register(RegisterStatus status, Account account) {
        public static Register success(Account account) {
            return new Register(RegisterStatus.SUCCESS, account);
        }

        public static Register failure(RegisterStatus status) {
            return new Register(status, null);
        }
    }

    public enum ChangePasswordStatus {
        SUCCESS,
        WRONG_PASSWORD,
        SAME_AS_OLD,
        PASSWORD_TOO_SHORT,
        PASSWORD_TOO_LONG,
        PASSWORD_TOO_LONG_BYTES,
        NOT_FOUND,
        ERROR
    }

    public record ChangePassword(ChangePasswordStatus status) {
        public boolean ok() {
            return status == ChangePasswordStatus.SUCCESS;
        }
    }

    public enum BindStatus {
        SUCCESS,
        ALREADY_BOUND,
        NOT_BOUND,
        EMAIL_INVALID,
        DOMAIN_NOT_ALLOWED,
        EMAIL_LIMIT,
        QQ_LIMIT,
        DISABLED,
        VERIFICATION_CANCELLED,
        VERIFICATION_EXPIRED,
        VERIFICATION_FAILED,
        ERROR
    }

    public record Bind(BindStatus status, String value) {
        public static Bind success(String value) {
            return new Bind(BindStatus.SUCCESS, value);
        }

        public static Bind failure(BindStatus status) {
            return new Bind(status, null);
        }

        public boolean ok() {
            return status == BindStatus.SUCCESS;
        }
    }
}
