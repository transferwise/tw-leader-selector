package com.transferwise.common.leaderselector;

import java.time.Duration;

public interface LeaderSelectorLifecycle {
    void start();

    void stop();

    boolean hasStopped();

    boolean waitUntilStopped(Duration waitTime);

    boolean isWorking();
}
