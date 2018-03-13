package com.transferwise.common.leaderselector;

import java.util.concurrent.CompletableFuture;

public interface LeaderSelectorLifecycle {
	void start();

	CompletableFuture stop();

	boolean isWorking();
}
