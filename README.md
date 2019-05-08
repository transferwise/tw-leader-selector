# tw-leader-selector

Provides a proper leader selector recipe with following benefits compared to Curator's recipe.

- Not based on Thread interruptions (very bad and old practice), so the control over the selector is completely graceful.
Additional benefit from it is, that it is very quiet on graceful shutdown.
- Better model for most use cases, especially for "periodic background jobs". Also for use cases where halting and resuming
of some work is needed.
- Much easier for a developer to use, no need to implement nor even know about connection state listeners.
- Non-blocking on all steps.
- More robust and reliable. Has additional built-in assertions, including checking the actual leader's node data and 
comparing to our (assumed) leader's id.

Example code for simple use case:
```java
leaderSelector = new LeaderSelector("leader1", curatorFramework, 
		"/tw/leaderSelector/testApp/leader1", executorService,
		control -> {
			log.info("I'm now the leader and will do some work.");
		});
```

When you have a long running code, you should periodically ask if you may continue to be a leader or should stop.
It is essential, that this checking is happening at least more often than Zookeeper's session timeout, which maximum is only
20 x Zookeeper ticks = 40s, but the more frequent those checks are the better.
If you are doing transactional work, it is advised to do a check just before the commit.
```java
leaderSelector = new LeaderSelector("leader2", curatorFramework, 
		"/tw/leaderSelector/testApp/leader2", executorService,
		control -> {
			for (int i=0; i<10; i++){
				if (control.shouldStop()){
					return;
				}
				log.info("Doing work for chunk " + i + ".");
				ExceptionUtils.doUnchecked(() -> Thread.sleep(1000));
			}
		});
```

And the state has some more convenient methods for asynchronous work, `waitUntilStop()`
