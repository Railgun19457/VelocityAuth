package io.github.railgun19457.velocityauth.storage;

/**
 * 数据层异常。DAO 内部把 {@link java.sql.SQLException} 包装为此异常，
 * 避免调用方被受检异常污染；事务失败时保留原始 cause。
 */
public final class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
