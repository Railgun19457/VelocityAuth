package io.github.railgun19457.velocityauth.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 异步执行器：所有 DB / SMTP / 网络 IO 都不得阻塞事件循环（见 IMPLEMENTATION.md 5.3-8）。
 * <p>
 * 使用虚拟线程，任务量不大时开销极低。
 */
public final class AsyncExecutor implements Executor, AutoCloseable {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    @Override
    public void execute(Runnable command) {
        run(command);
    }

    public void run(Runnable task) {
        if (closed) {
            return;
        }
        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable ignored) {
                // 调用方负责记录；此处兜底避免虚拟线程静默失败
            }
        });
    }

    public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("执行器已关闭"));
            return future;
        }
        executor.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdown();
    }
}
