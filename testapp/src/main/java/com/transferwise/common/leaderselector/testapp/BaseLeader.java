package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.leaderselector.Leader;
import com.transferwise.common.leaderselector.LeaderSelector;
import com.transferwise.common.leaderselector.LeaderSelectorLifecycle;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public abstract class BaseLeader implements SmartLifecycle {

  private LeaderSelectorLifecycle leaderSelector;

  @Autowired
  private CuratorFramework curatorFramework;

  @Autowired
  private Environment env;

  @PostConstruct
  public void init() {
    if (!"false".equalsIgnoreCase(env.getProperty(getLeaderId() + ".enabled"))) {
      ExecutorService executorService = Executors.newCachedThreadPool();
      //noinspection deprecation
      leaderSelector = new LeaderSelector(curatorFramework,
          "/tw/leaderSelector/testApp/" + getLeaderId(), executorService,
          getLeader());
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
