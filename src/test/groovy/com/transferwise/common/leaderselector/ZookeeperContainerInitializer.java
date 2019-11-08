package com.transferwise.common.leaderselector;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.GenericContainer;

import java.util.Collections;

@Slf4j
public class ZookeeperContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final int ZOOKEEPER_PORT = 2181;

    public static GenericContainer zookeeperInstance;

    @Override
    public void initialize(@NotNull ConfigurableApplicationContext applicationContext) {
        zookeeperInstance = startServiceZookeeperContainer(applicationContext);
    }

    private GenericContainer startServiceZookeeperContainer(ConfigurableApplicationContext appContext) {
        GenericContainer zookeeper = new GenericContainer("bitnami/zookeeper:3.5.6")
            .withNetworkAliases("zk-service")
            .withEnv("ZOO_STANDALONE_ENABLED", "true")
            .withEnv("ALLOW_ANONYMOUS_LOGIN", "yes")
            .withExposedPorts(ZOOKEEPER_PORT);

        zookeeper.start();
        Integer zkPort = zookeeper.getMappedPort(ZOOKEEPER_PORT);

        // Allow Zookeeper to be restarted without needing to reconfigure CuratorFramework port.
        zookeeper.setPortBindings(Collections.singletonList("" + zkPort + ":" + ZOOKEEPER_PORT));

        String connectString = "localhost:" + zkPort;
        TestPropertySourceUtils
            .addInlinedPropertiesToEnvironment(appContext, "zookeeper.connect-string=" + connectString);

        log.info("Zookeeper running at '{}'.", connectString);

        return zookeeper;
    }
}
