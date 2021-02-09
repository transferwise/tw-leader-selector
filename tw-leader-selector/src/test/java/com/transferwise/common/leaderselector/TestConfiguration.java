package com.transferwise.common.leaderselector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@SpringBootApplication
public class TestConfiguration {

  @Bean
  public LostCountingConnectionStateListener lostCountingConnectionStateListener() {
    return new LostCountingConnectionStateListener();
  }

  @Bean
  @ConditionalOnMissingBean(SharedReentrantLockBuilderFactory.class)
  public DefaultSharedReentrantLockBuilderFactory twLsSharedReentrantLockBuilderFactory() {
    return new DefaultSharedReentrantLockBuilderFactory();
  }

}
