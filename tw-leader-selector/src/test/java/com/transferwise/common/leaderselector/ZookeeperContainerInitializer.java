package com.transferwise.common.leaderselector;

import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.GenericContainer;

@Slf4j
public class ZookeeperContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  private static final int ZOOKEEPER_PORT = 2181;

  public static GenericContainer zookeeperInstance;

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    zookeeperInstance = startServiceZookeeperContainer(applicationContext);
  }

  private GenericContainer startServiceZookeeperContainer(ConfigurableApplicationContext appContext) {
    GenericContainer zookeeper = new GenericContainer("bitnami/zookeeper:3.5.5")
        .withNetworkAliases("zk-service")
        .withEnv("ZOO_STANDALONE_ENABLED", "true")
        .withEnv("ALLOW_ANONYMOUS_LOGIN", "yes")
        .withExposedPorts(ZOOKEEPER_PORT);

    zookeeper.start();
    Integer zkPort = zookeeper.getMappedPort(ZOOKEEPER_PORT);

    // Allow Zookeeper to be restarted without needing to reconfigure CuratorFramework port.
    zookeeper.setPortBindings(Collections.singletonList("" + zkPort + ":" + ZOOKEEPER_PORT));

    var connectHost = zookeeper.getHost();
    var connectString = connectHost + ":" + zkPort;

    TestPropertySourceUtils
        .addInlinedPropertiesToEnvironment(appContext, "tw-curator.zookeeper-connect-string=" + connectString);

    log.info("Zookeeper running at '{}'.", connectString);

    return zookeeper;
  }

}
