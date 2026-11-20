package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.storage.AccountRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 按账号累计本代理上至少有一条已认证连接的时间。 */
public final class OnlineTimeTracker implements AutoCloseable {

    private final AccountRepository accounts;
    private final LongSupplier clock;
    private final Map<Long, OnlineTime> times = new HashMap<>();
    private boolean closed;

    private static final class OnlineTime {
        int connections;
        long startedAt;
        long pending;

        long unflushed(long now) {
            return pending + (connections == 0 ? 0 : now - startedAt);
        }
    }

    public OnlineTimeTracker(AccountRepository accounts) {
        this(accounts, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    OnlineTimeTracker(AccountRepository accounts, LongSupplier clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    void connected(long accountId) {
        synchronized (times) {
            if (closed) {
                return;
            }
            OnlineTime time = times.computeIfAbsent(accountId, ignored -> new OnlineTime());
            if (time.connections++ == 0) {
                time.startedAt = clock.getAsLong();
            }
        }
    }

    void disconnected(long accountId) {
        synchronized (times) {
            OnlineTime time = times.get(accountId);
            if (time != null && time.connections > 0 && --time.connections == 0) {
                time.pending += clock.getAsLong() - time.startedAt;
            }
        }
    }

    /** 读取已保存的时长和截至查询时仍未保存的时长。 */
    public synchronized long totalMillis(long accountId) {
        long stored = accounts.onlineMillis(accountId);
        synchronized (times) {
            OnlineTime time = times.get(accountId);
            return stored + (time == null ? 0 : time.unflushed(clock.getAsLong()));
        }
    }

    /** 写入已结算与正在进行的会话增量，失败时留待下次提交。 */
    public synchronized void flush() {
        Map<Long, Long> batch = new TreeMap<>();
        synchronized (times) {
            long now = clock.getAsLong();
            times.forEach((id, time) -> {
                time.pending = time.unflushed(now);
                if (time.connections > 0) {
                    time.startedAt = now;
                }
                if (time.pending > 0) {
                    batch.put(id, time.pending);
                }
            });
            times.values().removeIf(time -> time.connections == 0 && time.pending == 0);
        }
        if (batch.isEmpty()) {
            return;
        }
        // 会话事件只持有 times 锁，可以在写库期间继续开始或结束计时。
        accounts.addOnlineMillis(batch);
        synchronized (times) {
            batch.forEach((id, elapsed) -> times.get(id).pending -= elapsed);
            times.values().removeIf(time -> time.connections == 0 && time.pending == 0);
        }
    }

    @Override
    public synchronized void close() {
        synchronized (times) {
            long now = clock.getAsLong();
            closed = true;
            times.values().forEach(time -> {
                time.pending = time.unflushed(now);
                time.connections = 0;
            });
        }
        flush();
    }
}
