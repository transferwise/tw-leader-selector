package com.transferwise.common.leaderselector;

import com.google.common.base.Preconditions;
import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.common.baseutils.clock.ClockHolder;
import com.transferwise.common.baseutils.concurrency.LockUtils;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LeaderSelectorV2 implements LeaderSelectorLifecycle {

  private final Builder config;

  private volatile boolean yieldRequested;
  private volatile boolean working;
  private volatile RunningState runningState = RunningState.STOPPED;
  private volatile RunningState runningStateIntent = RunningState.STOPPED;

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
    LockUtils.withLock(stateLock, () -> {
      if (runningState == RunningState.RUNNING) {
        throw new IllegalStateException("Leader selector is already running.");
      }
      runningStateIntent = RunningState.RUNNING;
      runningState = RunningState.RUNNING;
    });

    config.executorService.submit(() -> {
      while (runningStateIntent == RunningState.RUNNING) {
        try {
          while (runningStateIntent == RunningState.RUNNING) {
            long timeToSleepMs = lastWorkTryingTimeMs == -1 ? -1 : lastWorkTryingTimeMs - currentTimeMillis() + config.minimumWorkTime.toMillis();
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

      LockUtils.withLock(stateLock, () -> {
        runningState = RunningState.STOPPED;
        stateCondition.signalAll();
      });
    });
  }

  @Override
  public void stop() {
    log.debug(config.lock.getPath() + ": stopping.");
    LockUtils.withLock(stateLock, () -> {
      runningStateIntent = RunningState.STOPPED;
      stateCondition.signalAll();
    });
  }

  @Override
  public boolean hasStopped() {
    return LockUtils.withLock(stateLock, () -> runningState != RunningState.RUNNING);
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
        if (runningStateIntent != RunningState.RUNNING) {
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
        if (runningStateIntent != RunningState.RUNNING) {
          return false;
        }
        working = true;
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
    yieldRequested = false;
    config.leader.work(new Leader.Control() {
      @Override
      public boolean shouldStop() {
        return runningStateIntent != RunningState.RUNNING || !considerAsLeader();
      }

      @Override
      public boolean waitUntilShouldStop(Duration waitTime) {
        long start = currentTimeMillis();
        while (currentTimeMillis() < start + waitTime.toMillis()) {
          if (shouldStop()) {
            return true;
          }
          if (yieldRequested) {
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
          try {
            stopLogic.run();
          } catch (Throwable t) {
            log.error("Running stop logic failed.", t);
          }
        }
      }

      @Override
      public void yield() {
        LockUtils.withLock(stateLock, () -> {
          yieldRequested = true;
          stateCondition.signalAll();
        });
      }
    });
    log.debug(config.lock.getPath() + ": a leader finished working.");
  }

  private boolean considerAsLeader() {
    return config.lock.considerAsOwned();
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ignored) {
      // We have other mechanisms to make sure, that things get closed down when executor(s) are shutdown.
      Thread.interrupted();
    }
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
     * The minimum interval between taking leaderships. It is non-zero by default, so novice users can not overload the Zookeeper cluster. It is very
     * rarely needed to be changed.
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
     *
     * <p>&lt;null> has special value of indefinite iterations until explicit stop is called.
     *
     * <p>This parameter can be useful for one-time workloads.
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

  protected enum RunningState {
    RUNNING, STOPPED
  }
}
