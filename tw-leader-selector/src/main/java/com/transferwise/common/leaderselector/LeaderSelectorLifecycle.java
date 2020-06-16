package com.transferwise.common.leaderselector;

import java.time.Duration;

public interface LeaderSelectorLifecycle {

  void start();

  void stop();

  boolean hasStopped();

  @SuppressWarnings("UnusedReturnValue")
  boolean waitUntilStopped(Duration waitTime);

  boolean isWorking();
}
