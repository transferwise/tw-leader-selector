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
class LeaderSelectorV2IntSpec extends Specification {
    @Autowired
    private Environment environment
    @Autowired
    private CuratorFramework curatorFramework
    @Autowired
    private LostCountingConnectionStateListener lostCountingConnectionStateListener
    @Shared
    private ExecutorService executorService

    def setupSpec() {
        executorService = Executors.newCachedThreadPool()
    }

    def cleanup() {
        TestClock.reset()
    }

    private def builder() {
        SharedReentrantLock lock = new SharedReentrantLock.Builder()
                .setCuratorFramework(curatorFramework).setLostCountingConnectionStateListener(lostCountingConnectionStateListener)
                .setLockPath("/tw/leader").build()
        return new LeaderSelectorV2.Builder().setLock(lock).setExecutorService(executorService)
    }

    def "code in leader can be executed"() {
        given:
            AtomicInteger workCount = new AtomicInteger()
        when:
            LeaderSelectorV2 leaderSelector1 = builder().setLeader({ control ->
                workCount.incrementAndGet()
                log.info("Doing some work in leader1.")
            }).build()

            leaderSelector1.start()

            LeaderSelectorV2 leaderSelector2 = builder().setLeader({ control ->
                workCount.incrementAndGet()
                log.info("Doing some work in leader2.")
            }).build()
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

            List<LeaderSelectorV2> leaderSelectors = []
            for (int i = 0; i < N; i++) {
                leaderSelectors.add(builder().setLeader({ control ->
                    int concurrency = concurrentExecutions.incrementAndGet()
                    if (concurrency > 1) {
                        concurrencyControlFailed = true
                    }
                    Thread.sleep(1)
                    executionsCount.incrementAndGet()
                    concurrentExecutions.decrementAndGet()
                }).setMinimumWorkTime(Duration.ofMillis(0)).build())
            }
        when:
            leaderSelectors.forEach { it.start() }
            await().until() { executionsCount.get() > M }
            leaderSelectors.forEach { it.stop() }
            leaderSelectors.forEach { it.waitUntilStopped(Duration.ofSeconds(10)) }
        then:
            !concurrencyControlFailed
    }

    def "zookeeper connection loss will halt the leader work"() {
        given:
            AtomicInteger startCount = new AtomicInteger()
            AtomicInteger stopCount = new AtomicInteger()
            TestClock testClock = TestClock.createAndRegister()
        when:
            def leaderSelector = builder().setLeader({ control ->
                log.info("Starting leader work.")
                startCount.incrementAndGet()
                control.waitUntilShouldStop(Duration.ofMinutes(1))
                stopCount.incrementAndGet()
                log.info("Stopped leader work.")
            }).build()
            leaderSelector.start()
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
            leaderSelector.stop()
            await().until() { stopCount.get() == 2 }
            leaderSelector.waitUntilStopped(Duration.ofSeconds(10))
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
            def leaderSelector = builder().setLeader({ control ->
                log.info("Starting leader work.")
                startCount.incrementAndGet()
                control.waitUntilShouldStop(Duration.ofMinutes(1))
                stopCount.incrementAndGet()
                log.info("Stopped leader work.")
            }).build()
            leaderSelector.start()
        then:
            startCount.get() == 0
        when:
            zookeeperInstance.start()
            log.info("Zookeeper started.")
            await().until() { startCount.get() == 1 }
        then:
            1 == 1
        when: 'cleanup'
            leaderSelector.stop()
            leaderSelector.waitUntilStopped(Duration.ofSeconds(10))
        then:
            await().until() { stopCount.get() == 1 }
    }

    // Useful for automatic tests and tricky cases
    def "leader selector can be restarted"() {
        given:
            def startsCount = new AtomicInteger()
            def stopsCount = new AtomicInteger()

            def leaderSelector = builder().setLeader(
                    { control ->
                        startsCount.incrementAndGet()
                        control.waitUntilShouldStop(Duration.ofSeconds(10))
                        stopsCount.incrementAndGet()
                    }).build()
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
            await().until() { leaderSelector.waitUntilStopped(Duration.ofSeconds(10)) }
        then:
            stopsCount.get() == 2
    }

    def "when zookeeper is initially down, we don't wait forever to acquire a lock"() {
        given:
            zookeeperInstance.stop()
            log.info("Zookeeper stopped.")
        when:
            def lock = new SharedReentrantLock.Builder().setCuratorFramework(curatorFramework)
                    .setLostCountingConnectionStateListener(lostCountingConnectionStateListener)
                    .setLockPath("/tw/leader")
                    .setServerSideCheck(true).build()

            boolean acquired = lock.acquire(Duration.ofSeconds(5))
        then:
            !acquired
        when:
            zookeeperInstance.start()
            log.info("Zookeeper started.")
            acquired = lock.acquire(Duration.ofSeconds(55))
        then:
            acquired
            lock.isOwned(Duration.ofSeconds(5))
        when:
            lock.release()
        then:
            !lock.isOwned(Duration.ofSeconds(5))
            !lock.considerAsOwned()
    }

    def "asynchronous code can request a leader yielding"() {
        given:
            AtomicInteger startCount = new AtomicInteger()
            AtomicInteger stopCount = new AtomicInteger()
        when:
            LeaderSelectorV2 leaderSelector1 = builder().setLeader({ control ->
                if (startCount.get() < 5) {
                    control.workAsyncUntilShouldStop({
                        log.info("Start logic.")
                        startCount.incrementAndGet()
                        control.yield()
                    }, {
                        log.info("Stop logic.")
                        stopCount.incrementAndGet()
                    })
                }
            }).build()

            leaderSelector1.start()

            await().until({
                startCount.get() == 5 && stopCount.get() == 5
            })

            leaderSelector1.stop()
            leaderSelector1.hasStopped()
        then:
            1 == 1
    }
}
