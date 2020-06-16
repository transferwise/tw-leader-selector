package com.transferwise.common.leaderselector;

import com.transferwise.common.baseutils.ExceptionUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;

/**
 * Mostly meant to be a global singleton in a service which is provided for other components.
 */
@Slf4j
public class LostCountingConnectionStateListener implements ConnectionStateListener {

  private final Lock lock = new ReentrantLock();
  private final Condition condition = lock.newCondition();

  private volatile SessionState sessionState = null;
  private final AtomicLong lostCount = new AtomicLong();

  private final Clock clock = Clock.systemDefaultZone();

  @Override
  public void stateChanged(CuratorFramework client, ConnectionState connectionState) {
    lock.lock();
    try {
      boolean stateChanged = sessionState == null || sessionState.getConnectionState() != connectionState;
      if (stateChanged) {
        log.info("Curator connection state changed from {} to {}.",
            sessionState == null ? "<null>" : sessionState.connectionState, connectionState);
        if (connectionState == ConnectionState.LOST) {
          lostCount.incrementAndGet();
        }

        sessionState = new SessionState(connectionState, Instant.now(clock));

        condition.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  public long getConnectionLostCount() {
    return lostCount.get();
  }

  public boolean isSessionActive(long previousLostCount, Duration maxWait) {
    return ExceptionUtils.doUnchecked(() -> {
      lock.lock();
      try {
        long startTimeMs = clock.millis();
        long maxWaitMs = maxWait.toMillis();

        while ((clock.millis() - startTimeMs < maxWaitMs)) {
          if (previousLostCount < lostCount.get()) {
            return false;
          }
          ConnectionState currentState = sessionState == null ? null : sessionState.getConnectionState();
          if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.RECONNECTED) {
            return true;
          }
          if (currentState == ConnectionState.SUSPENDED || currentState == null) {
            boolean ignored = condition
                .await(maxWaitMs - (clock.millis() - startTimeMs), TimeUnit.MILLISECONDS);
          }
        }
        return false;
      } finally {
        lock.unlock();
      }
    });
  }

  public SessionState getSessionState() {
    return sessionState;
  }

  @Getter
  @Accessors(chain = true)
  public static class SessionState {

    private Instant stateTime;
    private ConnectionState connectionState;

    private SessionState(ConnectionState connectionState, Instant stateTime) {
      this.connectionState = connectionState;
      this.stateTime = stateTime;
    }

    public boolean isConnected() {
      return connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.READ_ONLY
          || connectionState == ConnectionState.RECONNECTED;
    }

    public boolean isSuspended() {
      return connectionState == ConnectionState.SUSPENDED;
    }
  }

}
