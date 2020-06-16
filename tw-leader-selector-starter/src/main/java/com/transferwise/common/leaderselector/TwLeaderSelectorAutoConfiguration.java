package com.transferwise.common.leaderselector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TwLeaderSelectorAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(LostCountingConnectionStateListener.class)
  public LostCountingConnectionStateListener twLsLostCountingConnectionStateListener() {
    return new LostCountingConnectionStateListener();
  }

  @Bean
  @ConditionalOnMissingBean(SharedReentrantLockBuilderFactory.class)
  public DefaultSharedReentrantLockBuilderFactory twLsSharedReentrantLockBuilderFactory() {
    return new DefaultSharedReentrantLockBuilderFactory();
  }

}
