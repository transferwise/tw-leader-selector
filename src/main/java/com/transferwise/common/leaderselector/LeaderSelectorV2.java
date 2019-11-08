package com.transferwise.common.leaderselector;

import com.google.common.base.Preconditions;
import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.common.baseutils.clock.ClockHolder;
import com.transferwise.common.baseutils.concurrency.LockUtils;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class LeaderSelectorV2 implements LeaderSelectorLifecycle {
    private final Builder config;

    private volatile boolean stopRequested;
    private volatile boolean working;
    private volatile boolean stopWorkIterationRequested = false;

    private final Lock stateLock;
    private final Condition stateCondition;

    private long workIterationsDone = 0;
    private long lastWorkTryingTimeMs = -1;

    private LeaderSelectorV2(Builder config) {
        this.config = config;
        this.stateLock = new ReentrantLock();
        this.stateCondition = stateLock.newCondition();
    }

    @Override
    public void start() {
        stopRequested = false;

        config.executorService.submit(() -> {
            while (!stopRequested) {
                try {
                    while (!stopRequested) {
                        long timeToSleepMs = lastWorkTryingTimeMs == -1 ? -1 :
                            lastWorkTryingTimeMs - currentTimeMillis() + config.minimumWorkTime.toMillis();
                        if (timeToSleepMs > 0) {
                            sleep(config.tickDuration.toMillis());
                        } else {
                            break;
                        }
                    }
                    lastWorkTryingTimeMs = currentTimeMillis();
                    tryToWork();
                } catch (Throwable t) {
                    log.error(t.getMessage(), t);
                    sleep(config.tickDuration.toMillis());
                }
            }
        });
    }

    @Override
    public void stop() {
        log.debug(config.lock.getPath() + ": stopping.");
        LockUtils.withLock(stateLock, () -> {
            stopRequested = true;
            stateCondition.signalAll();
        });
    }

    @Override
    public boolean hasStopped() {
        return LockUtils.withLock(stateLock, () -> stopRequested && !working);
    }

    @Override
    public boolean waitUntilStopped(Duration waitTime) {
        long start = currentTimeMillis();
        while (currentTimeMillis() < start + waitTime.toMillis()) {
            if (hasStopped()) {
                return true;
            }
            LockUtils.withLock(stateLock, () -> ExceptionUtils.doUnchecked(() -> {
                boolean ignored = stateCondition
                    .await(start + waitTime.toMillis() - currentTimeMillis(), TimeUnit.MILLISECONDS);
            }));
        }
        return hasStopped();
    }

    @Override
    public boolean isWorking() {
        return LockUtils.withLock(stateLock, () -> !working);
    }

    private void tryToWork() {
        boolean lockAcquired = false;
        try {
            while (!lockAcquired) {
                if (stopRequested) {
                    return;
                }
                try {
                    lockAcquired = config.lock.acquire(config.tickDuration);
                } catch (Throwable t) {
                    log.error("Trying to acquire mutex failed.", t);
                    sleep(config.tickDuration.toMillis());
                }
            }
            boolean doWork = LockUtils.withLock(stateLock, () -> {
                if (stopRequested) {
                    return false;
                }
                working = true;
                stopWorkIterationRequested = false;
                return true;
            });
            if (doWork) {
                workIterationsDone++;

                doWork();
            }
        } finally {
            if (lockAcquired) {
                try {
                    config.lock.release();
                } catch (Throwable t) {
                    log.error("Releasing mutex failed.", t);
                }
            }
            if (config.workIterationsUntilStop != null && workIterationsDone >= config.workIterationsUntilStop) {
                stop();
            }
            if (working) {
                LockUtils.withLock(stateLock, () -> {
                    working = false;
                    stateCondition.signalAll();
                });
            }
        }
    }

    private void doWork() {
        log.debug(config.lock.getPath() + ": a leader will work.");
        config.leader.work(new Leader.Control() {
            @Override
            public boolean shouldStop() {
                return stopRequested || stopWorkIterationRequested || !considerAsLeader();
            }

            @Override
            public boolean waitUntilShouldStop(Duration waitTime) {
                long start = currentTimeMillis();
                while (currentTimeMillis() < start + waitTime.toMillis()) {
                    if (shouldStop()) {
                        return true;
                    }
                    long timeFromStart = System.currentTimeMillis() - start;
                    LockUtils.withLock(stateLock, () -> {
                        long timeToWait = Math.min(waitTime.toMillis() - timeFromStart, config.tickDuration.toMillis());
                        ExceptionUtils.doUnchecked(() -> {
                            boolean ignored = stateCondition.await(timeToWait, TimeUnit.MILLISECONDS);
                        });
                    });
                }
                return shouldStop();
            }

            @Override
            @SuppressWarnings("checkstyle:magicnumber")
            public void workAsyncUntilShouldStop(Runnable startLogic, Runnable stopLogic) {
                try {
                    log.debug(config.lock.getPath() + ": running leader's start logic.");
                    startLogic.run();
                    waitUntilShouldStop(Duration.ofDays(3650));
                } finally {
                    log.debug(config.lock.getPath() + ": running leader's stop logic.");
                    stopLogic.run();
                }
            }
        });
        log.debug(config.lock.getPath() + ": a leader finished working.");
    }

    private boolean considerAsLeader() {
        return config.lock.considerAsOwned();
    }

    private void sleep(long ms) {
        ExceptionUtils.doUnchecked(() -> Thread.sleep(ms));
    }

    private long currentTimeMillis() {
        return ClockHolder.getClock().millis();
    }

    @Setter
    @Accessors(chain = true)
    public static class Builder {
        @NonNull
        private ILock lock;
        @NonNull
        private Leader leader;
        @NonNull
        private ExecutorService executorService;

        /**
         * The minimum interval between taking leaderships. It is non-zero by default, so novice users can not overload
         * the Zookeeper cluster. It is very rarely needed to be changed.
         */

        @NonNull
        private Duration minimumWorkTime = Duration.ofSeconds(2);
        /**
         * Main time interval, a tick. We will never block more than that.
         */
        @Setter
        @Accessors(chain = true)
        @NonNull
        private Duration tickDuration = Duration.ofSeconds(2);
        /**
         * Number of work iterations until the leader selector will be automatically stopped.
         * <p>
         * <null> has special value of indefinite iterations until explicit stop is called.
         * <p>
         * This parameter can be useful for one-time workloads.
         */
        @Setter
        @Accessors(chain = true)
        private Integer workIterationsUntilStop;

        public Builder() {
        }

        public LeaderSelectorV2 build() {
            Preconditions.checkNotNull(lock, "Lock is not provided.");
            Preconditions.checkNotNull(executorService, "ExecutorService is not provided.");
            Preconditions.checkNotNull(leader, "Leader is not provided.");

            return new LeaderSelectorV2(this);
        }
    }
}
