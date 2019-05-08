package com.transferwise.common.leaderselector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.GenericContainer;

import java.util.Arrays;

@Slf4j
public class ZookeeperContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final int ZOOKEEPER_PORT = 2181;

    public static GenericContainer zookeeperInstance;

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        zookeeperInstance = startServiceZookeeperContainer(applicationContext);
    }

    public GenericContainer startServiceZookeeperContainer(ConfigurableApplicationContext appContext) {
        GenericContainer zookeeper = new GenericContainer("zookeeper:3.5.4-beta")
            .withNetworkAliases("zk-service")
            .withEnv("ZOO_STANDALONE_ENABLED", "true")
            .withExposedPorts(ZOOKEEPER_PORT);

        zookeeper.start();
        Integer zkPort = zookeeper.getMappedPort(ZOOKEEPER_PORT);

        // Allow Zookeeper to be restarted without needing to reconfigure CuratorFramework port.
        zookeeper.setPortBindings(Arrays.asList("" + zkPort + ":" + ZOOKEEPER_PORT));

        String connectString = "localhost:" + zkPort;
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(appContext, "zookeeper.connect-string=" + connectString);

        log.info("Zookeeper running at '{}'.", connectString);

        return zookeeper;
    }
}
