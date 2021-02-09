package com.transferwise.common.leaderselector.test;

import com.transferwise.common.baseutils.clock.TestClock;
import org.junit.jupiter.api.AfterEach;

@BaseTestEnvironment
public class BaseIntTest {

  @AfterEach
  void baseSetup() {
    TestClock.reset();
  }
}
