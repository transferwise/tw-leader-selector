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
    @Autowired
    private Environment environment;

    @Bean(destroyMethod = "close")
    public CuratorFramework curatorFramework() {
        String connectString = "localhost:" + environment.getProperty("tw.testcontainers.zookeeper.mappedPort");

        CuratorFramework curatorFramework = CuratorFrameworkFactory.builder()
            .connectionTimeoutMs(5000)
            .sessionTimeoutMs(5000)
            .canBeReadOnly(false)
            .connectString(connectString)
            .retryPolicy(new RetryForever(5000))
            .build();

        curatorFramework.start();

        log.info("Curator framework started, using connect string '" + connectString + "'.");

        return curatorFramework;
    }
}
