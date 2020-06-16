package com.transferwise.common.leaderselector;

import com.google.common.base.Preconditions;
import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.common.baseutils.clock.ClockHolder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.NotThreadSafe;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.zookeeper.KeeperException;

@Slf4j
@NotThreadSafe
public class SharedReentrantLock implements ILock {

  private final Builder config;

  private final byte[] lockId = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
  private final String idString = new String(lockId, StandardCharsets.UTF_8);

  private final InterProcessMutex mutex;
  private long connectionLostCountOnAcquire;

  private Long lastServerSideCheckTimeMs;
  private Boolean lastServerSideCheckResult;

  private SharedReentrantLock(Builder config) {
    this.config = config;

    mutex = new InterProcessMutex(config.curatorFramework, config.lockPath) {
      @Override
      protected byte[] getLockNodeBytes() {
        return lockId;
      }
    };
  }

  @Override
  public boolean acquire(Duration duration) {
    connectionLostCountOnAcquire = config.lostCountingConnectionStateListener.getConnectionLostCount();
    try {
      boolean acquired = mutex.acquire(duration.toMillis(), TimeUnit.MILLISECONDS);
      if (acquired && (config.lostCountingConnectionStateListener
          .getConnectionLostCount() > connectionLostCountOnAcquire)) {
        mutex.release();
        return false;
      }
      return acquired;
    } catch (Throwable t) {
      log.error(t.getMessage(), t);
    }
    return false;
  }

  @Override
  public void release() {
    try {
      mutex.release();
    } catch (Throwable t) {
      log.error(t.getMessage(), t);
    }
  }

  @Override
  public boolean isOwned(Duration maxWait) {
    try {
      return isOwned0(maxWait) == OwnerStatus.OWN;
    } catch (Throwable t) {
      log.error(t.getMessage(), t);
      return false;
    }
  }

  private OwnerStatus isOwned0(Duration maxWait) {
    if (!mutex.isAcquiredInThisProcess()) {
      return OwnerStatus.NOT_ACQUIRED;
    }
    if (!config.lostCountingConnectionStateListener.isSessionActive(connectionLostCountOnAcquire, maxWait)) {
      return OwnerStatus.CONNECTION_LOST;
    }
    if (config.serverSideCheck && !isTheLockIdInTheServerOurs()) {
      return OwnerStatus.SERVER_SIDE_ID_DIFFERENT;
    }
    return OwnerStatus.OWN;
  }

  private enum OwnerStatus {
    OWN,
    CONNECTION_LOST,
    SERVER_SIDE_ID_DIFFERENT,
    NOT_ACQUIRED
  }

  @Override
  public void verifyOwnership(Duration maxWait) {
    OwnerStatus status = isOwned0(maxWait);
    if (status != OwnerStatus.OWN) {
      throw new IllegalStateException("Lock '" + config.lockPath + "' is not active anymore. Status: " + status);
    }
  }

  @Override
  public boolean considerAsOwned() {
    try {
      long connectionLostCount = config.lostCountingConnectionStateListener.getConnectionLostCount();

      if (!mutex.isAcquiredInThisProcess() || connectionLostCount > connectionLostCountOnAcquire) {
        return false;
      }

      LostCountingConnectionStateListener.SessionState sessionState = config.lostCountingConnectionStateListener
          .getSessionState();

      if (sessionState.isConnected()) {
        return (!config.serverSideCheck || isTheLockIdInTheServerOurs());
      } else if (sessionState.isSuspended()) {
        return Duration.between(sessionState.getStateTime(), Instant.now(ClockHolder.getClock()))
            .compareTo(config.considerActiveAfterSuspensionDuration) < 0;
      }
      return false;
    } catch (Throwable t) {
      log.error(t.getMessage(), t);
      return false;
    }
  }

  @Override
  public String getPath() {
    return config.lockPath;
  }

  private boolean isTheLockIdInTheServerOurs() {
    if (lastServerSideCheckTimeMs != null && config.serverSideCheckCacheDuration != null
        && (ClockHolder.getClock().millis() - lastServerSideCheckTimeMs < config.serverSideCheckCacheDuration.toMillis())) {
      return Boolean.TRUE.equals(lastServerSideCheckResult);
    }

    boolean result = false;
    try {
      byte[] serverSideId = fetchServerSideId();
      if (Arrays.equals(serverSideId, lockId)) {
        result = true;
      } else {
        log.warn("Server side lock id has somehow changed: was '{}', but expected '{}'.",
            serverSideId == null ? "<null>" :
                new String(serverSideId, StandardCharsets.UTF_8), idString);
      }
    } catch (Throwable t) {
      log.error("Lock '{}' was not able to fetch server side id.", idString, t);
    }

    lastServerSideCheckTimeMs = ClockHolder.getClock().millis();
    lastServerSideCheckResult = result;
    return result;
  }

  private byte[] fetchServerSideId() {
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
      return config.curatorFramework.getData().forPath(path);
    } catch (KeeperException.NoNodeException e) {
      return null;
    } catch (Exception e) {
      throw ExceptionUtils.toUnchecked(e);
    }
  }

  @Setter
  @Accessors(chain = true)
  public static class Builder {

    @NonNull
    private CuratorFramework curatorFramework;
    @NonNull
    private LostCountingConnectionStateListener lostCountingConnectionStateListener;
    @NonNull
    private String lockPath;
    private boolean serverSideCheck = true;
    @NonNull
    @SuppressWarnings("checkstyle:MagicNumber")
    private Duration serverSideCheckCacheDuration = Duration.ofSeconds(10);
    @NonNull
    @SuppressWarnings("checkstyle:MagicNumber")
    private Duration considerActiveAfterSuspensionDuration = Duration.ofSeconds(10);

    public Builder() {
    }

    public SharedReentrantLock build() {
      Preconditions.checkNotNull(curatorFramework, "CuratorFramework is not provided.");
      Preconditions.checkNotNull(lostCountingConnectionStateListener,
          "LostCountingConnectionStateListener is not provided.");
      Preconditions.checkArgument(StringUtils.isNotEmpty(lockPath), "LockPath not provided.");

      return new SharedReentrantLock(this);
    }
  }
}
