package com.transferwise.common.leaderselector

import com.transferwise.test.clock.TestClock
import com.transferwise.test.utils.TestWaitUtils
import groovy.util.logging.Slf4j
import org.apache.curator.framework.CuratorFramework
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.GenericContainer
import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@ActiveProfiles(profiles = ["integration"])
@SpringBootTest(classes = [TestConfiguration])
@Slf4j
class LeaderSelectorIntSpec extends Specification {
    @Autowired
    private GenericContainer zookeeper
    @Autowired
    private CuratorFramework curatorFramework
    @Shared
    private ExecutorService executorService

    def setupSpec() {
        executorService = Executors.newCachedThreadPool()
    }

    def setup(){
        // Allow Zookeeper to be restarted without needing to reconfigure CuratorFramework port.
        zookeeper.setPortBindings(["" + zookeeper.getMappedPort(zookeeper.getExposedPorts()[0]) + ":" + zookeeper.getExposedPorts()[0]])
    }

    def "code in leader can be executed"() {
        given:
            AtomicInteger workCount = new AtomicInteger();
        when:
            LeaderSelector leaderSelector1 = new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                workCount.incrementAndGet();
                log.info("Doing some work in leader1.");
            });
            leaderSelector1.start();

            LeaderSelector leaderSelector2 = new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                workCount.incrementAndGet();
                log.info("Doing some work in leader2.");
            });
            leaderSelector2.start();

            TestWaitUtils.waitFor({
                workCount.get() > 1;
            }, Duration.ofSeconds(10), Duration.ofMillis(10));

            leaderSelector1.stop();
            leaderSelector1.waitUntilStopped(Duration.ofSeconds(10));
            leaderSelector2.stop();
            leaderSelector2.waitUntilStopped(Duration.ofSeconds(10));
        then:
            1 == 1
    }

    def "only one leader can be executing at one time"() {
        given:
            int N = 100;
            int M = 1000;
            AtomicInteger executionsCount = new AtomicInteger();
            AtomicInteger concurrentExecutions = new AtomicInteger();
            boolean concurrencyControlFailed = false

            List<LeaderSelector> leaderSelectors = []
            for (int i = 0; i < N; i++) {
                leaderSelectors.add(new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                    int concurrency = concurrentExecutions.incrementAndGet();
                    if (concurrency > 1) {
                        concurrencyControlFailed = true
                    }
                    Thread.sleep(1);
                    executionsCount.incrementAndGet();
                    concurrentExecutions.decrementAndGet();
                }).setMinimumWorkTime(Duration.ofMillis(0)));
            }
        when:
            leaderSelectors.forEach { it.start() }
            TestWaitUtils.waitFor { executionsCount.get() > M }
            leaderSelectors.forEach { it.stop() }
        then:
            !concurrencyControlFailed
    }

    def "zookeeper connection loss will halt the leader work"() {
        given:
            AtomicInteger startCount = new AtomicInteger()
            AtomicInteger stopCount = new AtomicInteger()
            TestClock testClock = new TestClock();
        when:
            LeaderSelector leaderSelector1 = new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                log.info("Starting leader work.")
                startCount.incrementAndGet()
                control.waitUntilShouldStop(Duration.ofMinutes(1))
                stopCount.incrementAndGet()
                log.info("Stopped leader work.")
            }).setClock(testClock);
            leaderSelector1.start();
            TestWaitUtils.waitFor { startCount.get() == 1 }
        then:
            stopCount.get() == 0
        when:
            zookeeper.stop()
            log.info("Zookeeper stopped.")
            testClock.tick(Duration.ofSeconds(3));
        then:
            stopCount.get() == 0
        when:
            testClock.tick(Duration.ofMillis(2001))
            TestWaitUtils.waitFor { stopCount.get() == 1 }
        then:
            startCount.get() == 1
        when:
            zookeeper.start()
            log.info("Zookeeper restarted.")
            TestWaitUtils.waitFor { startCount.get() == 2 }
        then:
            stopCount.get() == 1
        when:
            leaderSelector1.stop();
            TestWaitUtils.waitFor { stopCount.get() == 2 }
        then:
            1 == 1
    }

    def "when zookeeper is initially down, the leader can start working after connection is established"() {
        given:
            AtomicInteger startCount = new AtomicInteger()
            AtomicInteger stopCount = new AtomicInteger()
            zookeeper.stop()
            log.info("Zookeeper stopped.")
        when:
            LeaderSelector leaderSelector1 = new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                log.info("Starting leader work.")
                startCount.incrementAndGet()
                control.waitUntilShouldStop(Duration.ofMinutes(1))
                stopCount.incrementAndGet()
                log.info("Stopped leader work.")
            })
            leaderSelector1.start();
        then:
            startCount.get() == 0
        when:
            zookeeper.start()
            log.info("Zookeeper started.")
            TestWaitUtils.waitFor { startCount.get() == 1 }
        then:
            1 == 1
    }
}
