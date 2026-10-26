package io.github.railgun19457.velocityauth.verify;

import java.time.Duration;

/**
 * 定时器抽象：便于单测替换 Velocity 调度器。
 */
@FunctionalInterface
public interface Timer {

    void schedule(Runnable task, Duration delay);
}
