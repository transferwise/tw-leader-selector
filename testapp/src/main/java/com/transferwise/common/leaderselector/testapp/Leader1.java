package com.transferwise.common.leaderselector.testapp;

import com.transferwise.common.leaderselector.LeaderSelectorLifecycle;
import com.transferwise.common.leaderselector.LeaderSelector;
import com.transferwise.common.utils.ExceptionUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class Leader1 implements SmartLifecycle {
	private LeaderSelectorLifecycle leaderSelector;
	private CuratorFramework curatorFramework;

	public Leader1(CuratorFramework curatorFramework) {
		this.curatorFramework = curatorFramework;
	}

	@PostConstruct
	public void init() {
		ExecutorService executorService = Executors.newCachedThreadPool();
		leaderSelector = new LeaderSelector("leader1", curatorFramework,
				"/tw/leaderSelector/testApp/leader1", executorService,
				state -> {
					if (state.shouldStop()){
						log.error("Leadership is not guaranteed anymore, we should stop.");
					}
					else {
						log.info("I'm now the leader.");
						ExceptionUtils.runUnchecked(() -> Thread.sleep(1));
					}
				});
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public void stop(Runnable callback) {
		leaderSelector.stop().thenAccept((r) -> {
			callback.run();
		});
	}

	@Override
	public void start() {
		leaderSelector.start();
	}

	@Override
	public void stop() {
		leaderSelector.stop();
	}

	@Override
	public boolean isRunning() {
		return false;
	}

	@Override
	public int getPhase() {
		return 0;
	}
}
