package com.transferwise.common.leaderselector;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@BaseTestEnvironment
@Slf4j
public class TwLeaderSelectorTest {

  @Autowired
  SharedReentrantLockBuilderFactory sharedReentrantLockBuilderFactory;

  @Test
  public void testLockBuilderFactory() {
    SharedReentrantLock lock = sharedReentrantLockBuilderFactory.createBuilder("/tw/test/lock").build();

    boolean acquired = lock.acquire(Duration.ofSeconds(1));
    try {
      log.info("Lock aquired.");
    } finally {
      if (acquired) {
        lock.release();
      }
    }
  }
}
