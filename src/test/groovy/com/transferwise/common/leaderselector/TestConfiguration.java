package com.transferwise.common.leaderselector;

import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryForever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@Slf4j
@EnableAutoConfiguration
public class TestConfiguration {
    private static final int NETWORK_TIMEOUT = 5000;

    @Autowired
    private Environment environment;

    @Bean(destroyMethod = "close")
    public CuratorFramework curatorFramework() {
        String connectString = environment.getProperty("zookeeper.connect-string");

        CuratorFramework curatorFramework = CuratorFrameworkFactory.builder()
            .connectionTimeoutMs(NETWORK_TIMEOUT)
            .sessionTimeoutMs(NETWORK_TIMEOUT)
            .canBeReadOnly(false)
            .connectString(connectString)
            .retryPolicy(new RetryForever(NETWORK_TIMEOUT))
            .build();

        curatorFramework.start();

        log.info("Curator framework started, using connect string '" + connectString + "'.");

        return curatorFramework;
    }
}
