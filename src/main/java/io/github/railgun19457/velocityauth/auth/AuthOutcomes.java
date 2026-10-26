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
    }

    public enum RegisterStatus {
        SUCCESS,
        NAME_TAKEN,
        NAME_INVALID,
        PASSWORD_TOO_SHORT,
        PASSWORD_TOO_LONG,
        PASSWORD_MISMATCH,
        GATE_REQUIRED,
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

    public record Register(RegisterStatus status, Account account, String gate) {
        public static Register success(Account account) {
            return new Register(RegisterStatus.SUCCESS, account, null);
        }

        public static Register failure(RegisterStatus status) {
            return new Register(status, null, null);
        }

        public static Register gate(String gate) {
            return new Register(RegisterStatus.GATE_REQUIRED, null, gate);
        }
    }

    public enum ChangePasswordStatus {
        SUCCESS,
        WRONG_PASSWORD,
        SAME_AS_OLD,
        PASSWORD_TOO_SHORT,
        PASSWORD_TOO_LONG,
        NOT_FOUND,
        ERROR
    }

    public record ChangePassword(ChangePasswordStatus status) {
        public boolean ok() {
            return status == ChangePasswordStatus.SUCCESS;
        }
    }
}
