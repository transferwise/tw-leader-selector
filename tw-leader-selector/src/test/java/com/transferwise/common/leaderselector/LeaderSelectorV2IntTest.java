package com.transferwise.common.leaderselector;

import static com.transferwise.common.leaderselector.ZookeeperContainerInitializer.zookeeperInstance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.transferwise.common.baseutils.clock.TestClock;
import com.transferwise.common.leaderselector.LeaderSelectorV2.Builder;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

@ActiveProfiles(profiles = {"integration"})
@SpringBootTest(classes = {TestConfiguration.class})
@ContextConfiguration(initializers = {ZookeeperContainerInitializer.class})
@Slf4j
@Disabled
class LeaderSelectorV2IntTest {

  @Autowired
  private SharedReentrantLockBuilderFactory sharedReentrantLockBuilderFactory;

  private ExecutorService executorService = Executors.newCachedThreadPool();

  @AfterEach
  void cleanup() {
    TestClock.reset();
  }

  @Test
  void zookeeperConnectionLossWillHaltTheLeaderWork() {
    var startCount = new AtomicInteger();
    var stopCount = new AtomicInteger();
    final var testClock = TestClock.createAndRegister();

    var leaderSelector = builder().setLeader(control -> {
      log.info("Starting leader work.");
      startCount.incrementAndGet();
      control.waitUntilShouldStop(Duration.ofMinutes(1));
      stopCount.incrementAndGet();
      log.info("Stopped leader work.");
    }).build();

    leaderSelector.start();
    await().until(() -> startCount.get() == 1);
    assertThat(stopCount.get()).isEqualTo(0);

    zookeeperInstance.stop();
    log.info("Zookeeper stopped.");
    testClock.tick(Duration.ofSeconds(3));
    assertThat(stopCount.get()).isZero();

    testClock.tick(Duration.ofMillis(2001));
    await().until(() -> stopCount.get() == 1);
    assertThat(startCount.get()).isEqualTo(1);

    zookeeperInstance.start();
    log.info("Zookeeper restarted.");
    await().until(() -> startCount.get() == 2);
    assertThat(stopCount.get()).isEqualTo(1);

    leaderSelector.stop();
    await().until(() -> stopCount.get() == 2);
    leaderSelector.waitUntilStopped(Duration.ofSeconds(10));
  }

  @Test
  void whenZookeeperIsInititallyDownTheLeaderCanStartWorkingAfterConnectionIsEstabished() {

    var startCount = new AtomicInteger();
    var stopCount = new AtomicInteger();
    zookeeperInstance.stop();
    log.info("Zookeeper stopped.");

    var leaderSelector = builder().setLeader(control -> {
      log.info("Starting leader work.");
      startCount.incrementAndGet();
      control.waitUntilShouldStop(Duration.ofMinutes(1));
      stopCount.incrementAndGet();
      log.info("Stopped leader work.");
    }).build();
    leaderSelector.start();
    assertThat(startCount.get()).isZero();

    zookeeperInstance.start();
    log.info("Zookeeper started.");
    await().until(() -> startCount.get() == 1);

    leaderSelector.stop();
    leaderSelector.waitUntilStopped(Duration.ofSeconds(10));

    await().until(() -> stopCount.get() == 1);
  }

  @Test
  void whenZookeeperIsInitiallyDownWeDontWaitForeverToAquireALock() {
    zookeeperInstance.stop();
    log.info("Zookeeper stopped.");
    var lock = sharedReentrantLockBuilderFactory.createBuilder("/tw/leader").build();
    var acquired = lock.acquire(Duration.ofSeconds(5));
    assertThat(acquired).isFalse();

    zookeeperInstance.start();
    log.info("Zookeeper started.");
    acquired = lock.acquire(Duration.ofSeconds(55));
    assertThat(acquired).isTrue();
    assertThat(lock.isOwned(Duration.ofSeconds(5))).isTrue();

    lock.release();
    assertThat(lock.isOwned(Duration.ofSeconds(5))).isFalse();
    assertThat(lock.considerAsOwned()).isFalse();
  }

  private Builder builder() {
    SharedReentrantLock lock = sharedReentrantLockBuilderFactory.createBuilder("/tw/leader").build();
    return new LeaderSelectorV2.Builder().setLock(lock).setExecutorService(executorService);
  }
}
