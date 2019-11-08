package com.transferwise.common.leaderselector;

import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@Slf4j
@EnableAutoConfiguration
public class TestConfiguration {
    private static final int NETWORK_TIMEOUT_MS = 5000;
    private static final int SESSION_TIMEOUT_MS = 5000;

    @Autowired
    private Environment environment;

    @Bean(destroyMethod = "close")
    public CuratorFramework curatorFramework() {
        String connectString = environment.getProperty("zookeeper.connect-string");

        CuratorFramework curatorFramework = CuratorFrameworkFactory.builder()
            .connectionTimeoutMs(NETWORK_TIMEOUT_MS)
            .sessionTimeoutMs(SESSION_TIMEOUT_MS)
            .canBeReadOnly(false)
            .connectString(connectString)
            .retryPolicy(new RetryOneTime(NETWORK_TIMEOUT_MS))
            .build();

        curatorFramework.start();

        log.info("Curator framework started, using connect string '" + connectString + "'.");

        return curatorFramework;
    }

    @Bean
    public LostCountingConnectionStateListener lostCountingConnectionStateListener(CuratorFramework curatorFramework){
        LostCountingConnectionStateListener listener = new LostCountingConnectionStateListener();
        curatorFramework.getConnectionStateListenable().addListener(listener);
        return listener;
    }
}
