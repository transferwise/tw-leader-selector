package com.transferwise.common.leaderselector;

public interface Leader {
	void work(LeadershipState state);

	interface LeadershipState {
		boolean shouldStop();

		void waitUntilShouldStop();

		boolean waitUntilShouldStopOrStateChanges();
	}
}
