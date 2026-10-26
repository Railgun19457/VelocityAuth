package io.github.railgun19457.velocityauth.auth;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑动窗口限流器（按 key 计数）。
 * <p>
 * 线程安全；窗口过期的记录在访问时惰性清理。
 */
public final class RateLimiter {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /** 尝试获取一次配额；成功返回 true。 */
    public boolean tryAcquire(String key, int limitPerWindow, Duration window) {
        if (limitPerWindow <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - window.toMillis();
        Deque<Long> timestamps = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limitPerWindow) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /** 剩余可用次数（仅用于提示）。 */
    public int remaining(String key, int limitPerWindow, Duration window) {
        Deque<Long> timestamps = windows.get(key);
        if (timestamps == null) {
            return limitPerWindow;
        }
        long cutoff = System.currentTimeMillis() - window.toMillis();
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            return Math.max(0, limitPerWindow - timestamps.size());
        }
    }

    /** 清空某个 key 的记录（例如登录成功后）。 */
    public void reset(String key) {
        windows.remove(key);
    }

    /** 清理长期未活动的窗口，避免内存增长。 */
    public void purge(Duration window) {
        long cutoff = System.currentTimeMillis() - window.toMillis();
        Iterator<Map.Entry<String, Deque<Long>>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> timestamps = it.next().getValue();
            synchronized (timestamps) {
                while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                    timestamps.pollFirst();
                }
                if (timestamps.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    public int trackedKeys() {
        return windows.size();
    }
}
