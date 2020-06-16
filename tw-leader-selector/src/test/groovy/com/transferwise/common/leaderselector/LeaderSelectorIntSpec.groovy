package com.transferwise.common.leaderselector

import com.transferwise.common.baseutils.clock.TestClock
import groovy.util.logging.Slf4j
import org.apache.curator.framework.CuratorFramework
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import static com.transferwise.common.leaderselector.ZookeeperContainerInitializer.zookeeperInstance
import static org.awaitility.Awaitility.await

@ActiveProfiles(profiles = ["integration"])
@SpringBootTest(classes = [TestConfiguration])
@ContextConfiguration(initializers = [ZookeeperContainerInitializer.class])
@Slf4j
@SuppressWarnings("GrDeprecatedAPIUsage")
class LeaderSelectorIntSpec extends Specification {
    @Autowired
    private Environment environment
    @Autowired
    private CuratorFramework curatorFramework
    @Shared
    private ExecutorService executorService

    def setupSpec() {
        executorService = Executors.newCachedThreadPool()
    }

    def "code in leader can be executed"() {
        given:
            AtomicInteger workCount = new AtomicInteger()
        when:
            LeaderSelector leaderSelector1 = new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                workCount.incrementAndGet()
                log.info("Doing some work in leader1.")
            })
            leaderSelector1.start()

            LeaderSelector leaderSelector2 = new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                workCount.incrementAndGet()
                log.info("Doing some work in leader2.")
            })
            leaderSelector2.start()

            await().until() { workCount.get() > 1 }

            leaderSelector1.stop()
            leaderSelector1.waitUntilStopped(Duration.ofSeconds(10))
            leaderSelector2.stop()
            leaderSelector2.waitUntilStopped(Duration.ofSeconds(10))
        then:
            1 == 1
    }

    def "only one leader can be executing at one time"() {
        given:
            int N = 100
            int M = 1000
            AtomicInteger executionsCount = new AtomicInteger()
            AtomicInteger concurrentExecutions = new AtomicInteger()
            boolean concurrencyControlFailed = false

            List<LeaderSelector> leaderSelectors = []
            for (int i = 0; i < N; i++) {
                leaderSelectors.add(new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                    int concurrency = concurrentExecutions.incrementAndGet()
                    if (concurrency > 1) {
                        concurrencyControlFailed = true
                    }
                    Thread.sleep(1)
                    executionsCount.incrementAndGet()
                    concurrentExecutions.decrementAndGet()
                }).setMinimumWorkTime(Duration.ofMillis(0)))
            }
        when:
            leaderSelectors.forEach { it.start() }
            await().until() { executionsCount.get() > M }
            leaderSelectors.forEach { it.stop() }
        then:
            !concurrencyControlFailed
    }

    def "zookeeper connection loss will halt the leader work"() {
        given:
            AtomicInteger startCount = new AtomicInteger()
            AtomicInteger stopCount = new AtomicInteger()
            TestClock testClock = new TestClock()
        when:
            LeaderSelector leaderSelector1 = new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                log.info("Starting leader work.")
                startCount.incrementAndGet()
                control.waitUntilShouldStop(Duration.ofMinutes(1))
                stopCount.incrementAndGet()
                log.info("Stopped leader work.")
            }).setClock(testClock)
            leaderSelector1.start()
            await().until() { startCount.get() == 1 }
        then:
            stopCount.get() == 0
        when:
            zookeeperInstance.stop()
            log.info("Zookeeper stopped.")
            testClock.tick(Duration.ofSeconds(3))
        then:
            stopCount.get() == 0
        when:
            testClock.tick(Duration.ofMillis(2001))
            await().until() { stopCount.get() == 1 }
        then:
            startCount.get() == 1
        when:
            zookeeperInstance.start()
            log.info("Zookeeper restarted.")
            await().until() { startCount.get() == 2 }
        then:
            stopCount.get() == 1
        when:
            leaderSelector1.stop()
            await().until() { stopCount.get() == 2 }
        then:
            1 == 1
    }

    def "when zookeeper is initially down, the leader can start working after connection is established"() {
        given:
            AtomicInteger startCount = new AtomicInteger()
            AtomicInteger stopCount = new AtomicInteger()
            zookeeperInstance.stop()
            log.info("Zookeeper stopped.")
        when:
            LeaderSelector leaderSelector1 = new LeaderSelector(curatorFramework, "/tw/leader", executorService, { control ->
                log.info("Starting leader work.")
                startCount.incrementAndGet()
                control.waitUntilShouldStop(Duration.ofMinutes(1))
                stopCount.incrementAndGet()
                log.info("Stopped leader work.")
            })
            leaderSelector1.start()
        then:
            startCount.get() == 0
        when:
            zookeeperInstance.start()
            log.info("Zookeeper started.")
            await().until() { startCount.get() == 1 }
        then:
            1 == 1
        when: 'cleanup'
            leaderSelector1.stop()
        then:
            await().until() { stopCount.get() == 1 }
    }

    // Useful for automatic tests and tricky cases
    def "leader selector can be restarted"() {
        given:
            def startsCount = new AtomicInteger()
            def stopsCount = new AtomicInteger()

            LeaderSelector leaderSelector = new LeaderSelector(curatorFramework, "/tw/leader", executorService,
                    { control ->
                        startsCount.incrementAndGet()
                        control.waitUntilShouldStop(Duration.ofSeconds(10))
                        stopsCount.incrementAndGet()
                    })
        when:
            leaderSelector.start()
            await().until() { startsCount.get() == 1 }
        and:
            leaderSelector.stop()
            await().until() { leaderSelector.hasStopped() }
        then:
            stopsCount.get() == 1
            startsCount.get() == 1
        when:
            leaderSelector.start()
            await().until() { startsCount.get() == 2 }
        then:
            !leaderSelector.hasStopped()
            stopsCount.get() == 1
        when:
            leaderSelector.stop()
            await().until() { leaderSelector.hasStopped() }
        then:
            stopsCount.get() == 2
    }
}
