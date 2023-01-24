package com.transferwise.common.leaderselector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.common.leaderselector.LeaderSelectorV2.Builder;
import com.transferwise.common.leaderselector.test.BaseIntTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
class LeaderSelectorV2StarterIntTest extends BaseIntTest {

  @Autowired
  private SharedReentrantLockBuilderFactory sharedReentrantLockBuilderFactory;

  private ExecutorService executorService = Executors.newCachedThreadPool();

  @Test
  void codeInLeaderSelectorCanBeExecuted() {
    var workCount = new AtomicInteger();
    var leaderSelector1 = builder().setLeader(control -> {
      workCount.incrementAndGet();
      log.info("Doing some work in leader1.");
    }).build();

    leaderSelector1.start();

    var leaderSelector2 = builder().setLeader(control -> {
      workCount.incrementAndGet();
      log.info("Doing some work in leader2.");
    }).build();

    leaderSelector2.start();

    await().until(() -> workCount.get() > 1);

    leaderSelector1.stop();
    leaderSelector1.waitUntilStopped(Duration.ofSeconds(10));
    leaderSelector2.stop();
    leaderSelector2.waitUntilStopped(Duration.ofSeconds(10));
  }

  @Test
  void onlyOneLeaderCanBeExecutingAtOneTime() {
    var leaderSelectorsCount = 100;
    var iterationsCount = 1000;
    var executionsCount = new AtomicInteger();
    var concurrentExecutions = new AtomicInteger();
    var concurrencyControlFailed = new AtomicBoolean(false);

    List<LeaderSelectorV2> leaderSelectors = new ArrayList<>();
    for (int i = 0; i < leaderSelectorsCount; i++) {
      leaderSelectors.add(builder().setLeader(control -> {
        var concurrency = concurrentExecutions.incrementAndGet();
        if (concurrency > 1) {
          concurrencyControlFailed.set(true);
        }
        ExceptionUtils.doUnchecked(() -> Thread.sleep(1));
        executionsCount.incrementAndGet();
        concurrentExecutions.decrementAndGet();
      }).setMinimumWorkTime(Duration.ofMillis(0)).build());
    }

    leaderSelectors.forEach(l -> l.start());
    await().until(() -> executionsCount.get() > iterationsCount);
    leaderSelectors.forEach(l -> l.stop());
    leaderSelectors.forEach(l -> l.waitUntilStopped(Duration.ofSeconds(10)));

    assertThat(concurrencyControlFailed.get()).isFalse();
  }

  // Useful for automatic tests and tricky cases
  @Test
  void leaderSelectorCanBeRestarted() {
    var startsCount = new AtomicInteger();
    var stopsCount = new AtomicInteger();

    var leaderSelector = builder().setLeader(
        control -> {
          startsCount.incrementAndGet();
          control.waitUntilShouldStop(Duration.ofSeconds(10));
          stopsCount.incrementAndGet();
        }).build();

    leaderSelector.start();
    await().until(() -> startsCount.get() == 1);

    leaderSelector.stop();
    await().until(() -> leaderSelector.hasStopped());
    assertThat(stopsCount.get()).isEqualTo(1);
    assertThat(startsCount.get()).isEqualTo(1);

    leaderSelector.start();
    await().until(() -> startsCount.get() == 2);
    assertThat(leaderSelector.hasStopped()).isFalse();
    assertThat(stopsCount.get()).isEqualTo(1);

    leaderSelector.stop();
    await().until(() -> leaderSelector.waitUntilStopped(Duration.ofSeconds(10)));
    assertThat(stopsCount.get()).isEqualTo(2);
  }

  @Test
  void asyncCodeCanRequestLeaderToYield() {
    var startCount = new AtomicInteger();
    var stopCount = new AtomicInteger();
    var leaderSelector = builder().setLeader(control -> {
      if (startCount.get() < 5) {
        control.workAsyncUntilShouldStop(() -> {
          log.info("Start logic.");
          startCount.incrementAndGet();
          control.yield();
        }, () -> {
          log.info("Stop logic.");
          stopCount.incrementAndGet();
        });
      }
    }).build();

    leaderSelector.start();

    await().until(() -> startCount.get() == 5 && stopCount.get() == 5);

    leaderSelector.stop();
    leaderSelector.hasStopped();
  }

  @Test
  @SneakyThrows
  void lockingWorks() {
    var tasksCount = 1000;
    var threadCount = 20;

    var lock = sharedReentrantLockBuilderFactory.createBuilder("/tw/leader").build();
    var counter = new MutableInt();
    var executors = Executors.newFixedThreadPool(threadCount);

    for (int i = 0; i < tasksCount; i++) {
      executors.submit(() -> {
        boolean acquired = lock.acquire(Duration.ofSeconds(20));
        if (acquired) {
          try {
            counter.setValue(counter.getValue() + 1);
          } finally {
            lock.release();
          }
        } else {
          log.error("Lock was not acquired.");
        }
      });
    }

    executors.shutdown();
    executors.awaitTermination(1, TimeUnit.MINUTES);
    assertThat(counter.getValue()).isEqualTo(tasksCount);
  }

  private Builder builder() {
    SharedReentrantLock lock = sharedReentrantLockBuilderFactory.createBuilder("/tw/leader").build();
    return new LeaderSelectorV2.Builder().setLock(lock).setExecutorService(executorService);
  }
}
