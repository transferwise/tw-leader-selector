package com.transferwise.common.leaderselector;

import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.common.baseutils.concurrency.LockUtils;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.zookeeper.KeeperException;

@Slf4j
// Use LeaderSelectorV2 instead.
@Deprecated
public class LeaderSelector implements LeaderSelectorLifecycle {

  private final InterProcessMutex mutex;
  private final Leader leader;
  private final CuratorFramework curatorFramework;
  private final ExecutorService executorService;
  private Clock clock;

  /**
   * The node path in Zookeeper, where we track the leadership.
   */
  private final String leaderPath;
  /**
   * Unique nodeId we will save into Zookeeper's leader-node, so we are able to later verify by other means if we still are a leader.
   */
  private final byte[] nodeId;
  /**
   * The minimum interval between taking leaderships. It is non-zero by default, so novice users can not overload the Zookeeper cluster. It is very
   * rarely needed to be changed.
   */
  private Duration minimumWorkTime = Duration.ofSeconds(2);
  /**
   * Main time interval, a tick. We will never block more than that.
   */
  private Duration tickDuration = Duration.ofSeconds(2);
  /**
   * How often do we check if we are still a leader. Value of -1 turns this off.
   * 
   * <p>After every this interval, we will request leader's node data and compare it to our nodeId.
   * 
   * <p>This kind of check is meant to be as an assertion to verify the correctness of LeaderSelector algorithms.
   */
  @SuppressWarnings("checkstyle:magicnumber")
  private Duration leaderGuaranteeCheckInterval = Duration.ofSeconds(10);
  /**
   * On a very unstable network we could get very rapid disconnections and reconnects while the Zookeeper session itself is stable.
   * 
   * <p>In this case we do not want to interrupt the leader's work too often. Only when the specified time passes after a disconnect, we will 
   * consider as
   * connection (and also the session) as lost and will request current leader to stop all work.
   */
  @SuppressWarnings("checkstyle:magicnumber")
  private Duration connectionLossConfirmedDuration = Duration.ofSeconds(5);
  /**
   * Number of work iterations until the leader selector will be automatically stopped.
   * 
   * <p>-1 has special value of indefinite iterations until explicit stop is called.
   * 
   * <p>This parameter can be useful for one-time workloads.
   */
  private int workIterationsUntilStop = -1;

  private final ConnectionStateListener connectionStateListener;

  private volatile boolean stopRequested;
  private volatile boolean working;
  private volatile long disconnectedTimestamp = -1;
  private volatile boolean stopWorkIterationRequested = false;

  private final Lock stateLock;
  private final Condition stateCondition;

  private volatile long lastLeadershipGuaranteeTestTime = 0;
  private volatile boolean lastLeadershipGuaranteeTestResult;

  private long workIterationsDone = 0;
  private long lastWorkTryingTimeMs = -1;

  public LeaderSelector(CuratorFramework curatorFramework, String leaderPath, ExecutorService executorService, Leader leader) {
    this.leader = leader;
    this.curatorFramework = curatorFramework;
    this.executorService = executorService;
    this.leaderPath = leaderPath;
    this.stateLock = new ReentrantLock();
    this.stateCondition = stateLock.newCondition();
    this.nodeId = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
    this.clock = Clock.systemUTC();

    this.mutex = new InterProcessMutex(curatorFramework, leaderPath) {
      @Override
      protected byte[] getLockNodeBytes() {
        return nodeId;
      }
    };

    this.connectionStateListener = (client, newState) -> LockUtils.withLock(stateLock, () -> {
      if (newState == ConnectionState.LOST) {
        log.debug(leaderPath + ": disconnected from Zookeeper, stopping current work iteration.");
        stopWorkIterationRequested = true;
        if (disconnectedTimestamp == -1) {
          disconnectedTimestamp = currentTimeMillis();
        }
      } else if (newState == ConnectionState.SUSPENDED) {
        log.debug(leaderPath + ": disconnected from Zookeeper.");
        if (disconnectedTimestamp == -1) {
          disconnectedTimestamp = currentTimeMillis();
        }
      } else if (newState == ConnectionState.RECONNECTED) {
        if (!considerAsConnected()) {
          log.debug(leaderPath + ": reconnected to Zookeeper, but too late.");
          stopWorkIterationRequested = true;
        } else {
          log.debug(leaderPath + ": reconnected to Zookeeper.");
        }
        disconnectedTimestamp = -1;
      } else if (newState == ConnectionState.CONNECTED) {
        log.debug(leaderPath + ": connected to Zookeeper.");
      }
      stateCondition.signalAll();
    });
  }

  public LeaderSelector setTickDuration(Duration tickDuration) {
    this.tickDuration = tickDuration;
    return this;
  }

  public LeaderSelector setLeaderGuaranteeCheckInterval(Duration leaderGuaranteeCheckInterval) {
    this.leaderGuaranteeCheckInterval = leaderGuaranteeCheckInterval;
    return this;
  }

  public LeaderSelector setConnectionLossConsideredTicks(Duration connectionLossConfirmedDuration) {
    this.connectionLossConfirmedDuration = connectionLossConfirmedDuration;
    return this;
  }

  public LeaderSelector setNumberOfWorkIterationsUntilStop(int workIterationsUntilStop) {
    this.workIterationsUntilStop = workIterationsUntilStop;
    return this;
  }

  public LeaderSelector setMinimumWorkTime(Duration minimumWorkTime) {
    this.minimumWorkTime = minimumWorkTime;
    return this;
  }

  public LeaderSelector setClock(Clock clock) {
    this.clock = clock;
    return this;
  }

  @Override
  public void start() {
    stopRequested = false;

    curatorFramework.getConnectionStateListenable().addListener(this.connectionStateListener, executorService);

    executorService.submit(() -> {
      while (!stopRequested) {
        try {
          if (disconnectedTimestamp != -1) {
            sleep(tickDuration.toMillis());
          } else {
            while (!stopRequested) {
              long timeToSleepMs = lastWorkTryingTimeMs == -1 ? -1 :
                  lastWorkTryingTimeMs - currentTimeMillis() + minimumWorkTime.toMillis();
              if (timeToSleepMs > 0) {
                sleep(tickDuration.toMillis());
              } else {
                break;
              }
            }
            lastWorkTryingTimeMs = currentTimeMillis();
            tryToWork();
          }
        } catch (Throwable t) {
          log.error(t.getMessage(), t);
          sleep(tickDuration.toMillis());
        }
      }
    });
  }

  @Override
  public void stop() {
    log.debug(leaderPath + ": stopping.");
    LockUtils.withLock(stateLock, () -> {
      curatorFramework.getConnectionStateListenable().removeListener(this.connectionStateListener);
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
        if (stopRequested || disconnectedTimestamp != -1) {
          return;
        }
        try {
          lockAcquired = mutex.acquire(tickDuration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
          log.error("Trying to acquire mutex failed.", t);
          sleep(tickDuration.toMillis());
        }
      }
      boolean doWork = LockUtils.withLock(stateLock, () -> {
        if (stopRequested || !considerAsConnected()) {
          return false;
        }
        working = true;
        stopWorkIterationRequested = false;
        return true;
      });
      if (doWork) {
        lastLeadershipGuaranteeTestTime = currentTimeMillis();
        lastLeadershipGuaranteeTestResult = true;
        workIterationsDone++;

        try {
          doWork();
        } finally {
          LockUtils.withLock(stateLock, () -> {
            working = false;
            stateCondition.signalAll();
          });
        }
      }
    } finally {
      if (lockAcquired) {
        try {
          mutex.release();
        } catch (Throwable t) {
          log.error("Releasing mutex failed.", t);
        }
      }
      if (workIterationsUntilStop != -1 && workIterationsDone >= workIterationsUntilStop) {
        stop();
      }
    }
  }

  private void doWork() {
    log.debug(leaderPath + ": a leader will work.");
    leader.work(new Leader.Control() {
      @Override
      public boolean shouldStop() {
        return stopRequested || stopWorkIterationRequested || !considerAsConnected() || !isNodeStillTheLeader();
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
            long timeToWait = Math.min(waitTime.toMillis() - timeFromStart, tickDuration.toMillis());

            long disconnectedTimestampTmp = disconnectedTimestamp;
            if (disconnectedTimestampTmp != -1) {
              timeToWait = Math.min(timeToWait, disconnectedTimestampTmp + connectionLossConfirmedDuration
                  .toMillis() - System.currentTimeMillis());
            }
            if (lastLeadershipGuaranteeTestTime != 0 && leaderGuaranteeCheckInterval
                .toMillis() > 0) { // We should still avoid cpu burn.
              timeToWait = Math.min(timeToWait,
                  lastLeadershipGuaranteeTestTime + leaderGuaranteeCheckInterval
                      .toMillis() - System.currentTimeMillis());
            }
            long timeToWaitFinal = timeToWait;
            ExceptionUtils.doUnchecked(() -> {
              boolean ignored = stateCondition.await(timeToWaitFinal, TimeUnit.MILLISECONDS);
            });
          });
        }
        return shouldStop();
      }

      @Override
      @SuppressWarnings("checkstyle:magicnumber")
      public void workAsyncUntilShouldStop(Runnable startLogic, Runnable stopLogic) {
        try {
          log.debug(leaderPath + ": running leader's start logic.");
          startLogic.run();
          waitUntilShouldStop(Duration.ofDays(3650));
        } finally {
          log.debug(leaderPath + ": running leader's stop logic.");
          stopLogic.run();
        }
      }

      @Override
      public void yield() {
        throw new UnsupportedOperationException("Yield is not supported in V1, please move to V2.");
      }
    });
    log.debug(leaderPath + ": a leader finished working.");
  }

  private boolean considerAsConnected() {
    return disconnectedTimestamp == -1 || disconnectedTimestamp + connectionLossConfirmedDuration
        .toMillis() > currentTimeMillis();
  }

  private boolean isNodeStillTheLeader() {
    return LockUtils.withLock(stateLock, () -> {
      boolean currentLastLeadershipGuaranteeTestResult = lastLeadershipGuaranteeTestResult;
      if (lastLeadershipGuaranteeTestTime == 0 || currentTimeMillis() > lastLeadershipGuaranteeTestTime + leaderGuaranteeCheckInterval
          .toMillis()) {
        try {
          byte[] currentLeaderId = fetchCurrentLeaderId();

          lastLeadershipGuaranteeTestResult = Arrays.equals(currentLeaderId, nodeId);
          if (!lastLeadershipGuaranteeTestResult) {
            String currentLeaderIdSt = currentLeaderId == null ? null : new String(currentLeaderId,
                StandardCharsets.UTF_8);
            log.error("We have somehow lost leadership to a node with id '" + currentLeaderIdSt + "'.");
          }
        } catch (Throwable t) {
          lastLeadershipGuaranteeTestResult = false;
          log.error("Trying to acquire mutex failed.", t);
        } finally {
          lastLeadershipGuaranteeTestTime = currentTimeMillis();
        }
      }
      if (currentLastLeadershipGuaranteeTestResult != lastLeadershipGuaranteeTestResult) {
        log.debug(
            leaderPath + ": leadership guarantee result changed to " + lastLeadershipGuaranteeTestResult + ".");
        stateCondition.signalAll();
      }
      return lastLeadershipGuaranteeTestResult;
    });
  }

  private void sleep(long ms) {
    ExceptionUtils.doUnchecked(() -> Thread.sleep(ms));
  }

  private byte[] fetchCurrentLeaderId() {
    Collection<String> participantNodes = ExceptionUtils.doUnchecked(mutex::getParticipantNodes);
    if (participantNodes.size() > 0) {
      for (String path : participantNodes) {
        byte[] id = idForPath(path);

        if (id != null) {
          return id;
        }
      }
    }

    return null;
  }

  private byte[] idForPath(String path) {
    try {
      return curatorFramework.getData().forPath(path);
    } catch (KeeperException.NoNodeException e) {
      return null;
    } catch (Exception e) {
      throw ExceptionUtils.toUnchecked(e);
    }
  }

  private long currentTimeMillis() {
    return clock.millis();
  }
}
