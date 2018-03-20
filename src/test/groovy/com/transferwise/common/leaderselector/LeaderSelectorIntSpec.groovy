package com.transferwise.common.leaderselector

import com.playtika.test.kafka.checks.ZookeeperStatusCheck
import com.playtika.test.kafka.properties.ZookeeperConfigurationProperties
import com.transferwise.test.clock.TestClock
import com.transferwise.test.utils.TestWaitUtils
import groovy.util.logging.Slf4j
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryForever
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.FixedHostPortGenericContainer
import org.testcontainers.containers.GenericContainer
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import static com.playtika.test.common.utils.ContainerUtils.containerLogsConsumer

@Slf4j
class LeaderSelectorIntSpec extends Specification {
    @Shared
    private GenericContainer zookeeper
    @Shared
    private CuratorFramework curatorFramework
    @Shared
    private ExecutorService executorService

    def setupSpec() {
        GenericContainer zookeeper
        String zookeeperConnectString
        (zookeeper, zookeeperConnectString) = createZookeeper();
        this.zookeeper = zookeeper;
        zookeeper.start();

        curatorFramework = CuratorFrameworkFactory.builder()
            .connectString(zookeeperConnectString)
            .retryPolicy(new RetryForever(5000))
            .build()
        curatorFramework.start();

        executorService = Executors.newCachedThreadPool();
    }

    def cleanupSpec() {
        if (curatorFramework != null) {
            curatorFramework.close()
        }
        if (zookeeper != null) {
            zookeeper.stop()
        }
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

    private def createZookeeper() throws IOException {
        ZookeeperConfigurationProperties zookeeperProperties = new ZookeeperConfigurationProperties()
        zookeeperProperties.setDataFileSystemBind("/tmp/tw-leader-selector/test-containers/embedded-zk-data");
        zookeeperProperties.setTxnLogsFileSystemBind("/tmp/tw-leader-selector/test-containers/embedded-zk-txn-logs");
        zookeeperProperties.init();

        ZookeeperStatusCheck zookeeperStatusCheck = new ZookeeperStatusCheck(zookeeperProperties);

        String currentTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm-ss-nnnnnnnnn"));
        String zkData = Paths.get(zookeeperProperties.getDataFileSystemBind(), currentTimestamp).toAbsolutePath().toString();
        log.info("Writing zookeeper data to: {}", zkData);
        String zkTransactionLogs = Paths.get(zookeeperProperties.getTxnLogsFileSystemBind(), currentTimestamp).toAbsolutePath().toString();
        log.info("Writing zookeeper transaction logs to: {}", zkTransactionLogs);

        log.info("Starting zookeeper server. Docker image: {}", zookeeperProperties.getDockerImage());

        int mappingPort = zookeeperProperties.getZookeeperPort();
        GenericContainer zookeeper = new FixedHostPortGenericContainer<FixedHostPortGenericContainer>(zookeeperProperties.getDockerImage())
            .withLogConsumer(containerLogsConsumer(log))
            .withEnv("ZOOKEEPER_CLIENT_PORT", String.valueOf(mappingPort))
            .withFileSystemBind(zkData, "/var/lib/zookeeper/data", BindMode.READ_WRITE)
            .withFileSystemBind(zkTransactionLogs, "/var/lib/zookeeper/log", BindMode.READ_WRITE)
            .withExposedPorts(mappingPort)
            .withFixedExposedPort(mappingPort, mappingPort)
            .waitingFor(zookeeperStatusCheck);

        Integer port = zookeeperProperties.getZookeeperPort();
        String host = zookeeper.getContainerIpAddress();

        String zookeeperConnectString = String.format("%s:%d", host, port);

        return [zookeeper, zookeeperConnectString];
    }
}
