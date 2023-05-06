package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.leaderselector.ILock;
import com.transferwise.common.leaderselector.Leader;
import com.transferwise.common.leaderselector.LeaderSelectorLifecycle;
import com.transferwise.common.leaderselector.LeaderSelectorV2;
import com.transferwise.common.leaderselector.SharedReentrantLockBuilderFactory;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public abstract class BaseLeader implements SmartLifecycle, InitializingBean {

  private LeaderSelectorLifecycle leaderSelector;

  @Autowired
  private SharedReentrantLockBuilderFactory sharedReentrantLockBuilderFactory;

  @Autowired
  private Environment env;

  @Override
  public void afterPropertiesSet() {
    if (!"false".equalsIgnoreCase(env.getProperty(getLeaderId() + ".enabled"))) {
      ExecutorService executorService = Executors.newCachedThreadPool();
      ILock lock = sharedReentrantLockBuilderFactory.createBuilder("/tw/leaderSelector/testApp/" + getLeaderId()).build();
      leaderSelector = new LeaderSelectorV2.Builder().setLock(lock).setExecutorService(executorService).setLeader(getLeader()).build();
    }
  }

  protected abstract String getLeaderId();

  protected abstract Leader getLeader();

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  @Override
  public void stop() {
    leaderSelector.stop();
  }

  @Override
  @SuppressWarnings("checkstyle:magicnumber")
  public void stop(Runnable callback) {
    if (leaderSelector != null) {
      leaderSelector.stop();
      new Thread(() -> {
        leaderSelector.waitUntilStopped(Duration.ofDays(365));
        callback.run();
      }).start();
    }
  }

  @Override
  public void start() {
    if (leaderSelector != null) {
      leaderSelector.start();
    }
  }

  @Override
  public boolean isRunning() {
    return false;
  }

  @Override
  public int getPhase() {
    return 0;
  }
}
