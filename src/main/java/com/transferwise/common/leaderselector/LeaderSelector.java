package com.transferwise.common.leaderselector;

import com.transferwise.common.utils.ExceptionUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.zookeeper.KeeperException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.transferwise.common.utils.ExceptionUtils.callUnchecked;
import static com.transferwise.common.utils.ExceptionUtils.runUnchecked;

@Slf4j
public class LeaderSelector implements LeaderSelectorLifecycle {
	private InterProcessMutex mutex;
	private Leader leader;
	private CuratorFramework curatorFramework;
	private ExecutorService executorService;
	private String leaderPath;
	private byte[] nodeId;
	private String name;
	private Duration minimumWorkTime = Duration.ofSeconds(2);

	/**
	 * Main time interval, a tick. We will never block more than that.
	 */
	private Duration tickDuration = Duration.ofSeconds(2);
	/**
	 * How often do we check if we are still a leader. Value of -1 turns this off.
	 */
	private Duration leaderGuaranteeCheckInterval = Duration.ofSeconds(10);
	/**
	 * On a very unstable network we could get very rapid disconnections and reconnections while the Zookeeper session is stable.
	 * In this case we do not want to interrupt the leader's work too often.
	 */
	private Duration connectionLossConfirmedDuration = Duration.ofSeconds(5);
	/**
	 * Number of work iterations until the leader selector will be automatically stopped.
	 * -1 has special value of indefinite iterations until explicit stop is called.
	 */
	private int workIterationsUntilStop = -1;

	private ConnectionStateListener connectionStateListener;

	private volatile boolean stopRequested;
	private volatile boolean working;
	private volatile long disconnectedTimestamp = -1;
	private volatile boolean stopWorkIterationRequested = false;
	private volatile CompletableFuture<Void> stopFuture;

	private Lock stateLock;
	private Condition stateCondition;

	private long lastLeaderhipGuaranteeTestTime = 0;
	private boolean lastLeadershipGuranteeTestResult;

	private long workIterationsDone = 0;
	private long lastWorkTryingTimeMs = -1;

	public LeaderSelector(String name, CuratorFramework curatorFramework, String leaderPath, ExecutorService executorService, Leader leader) {
		this.leader = leader;
		this.curatorFramework = curatorFramework;
		this.executorService = executorService;
		this.leaderPath = leaderPath;
		this.stateLock = new ReentrantLock();
		this.stateCondition = stateLock.newCondition();
		this.nodeId = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
		this.name = name;

		this.mutex = new InterProcessMutex(curatorFramework, leaderPath) {
			@Override
			protected byte[] getLockNodeBytes() {
				return nodeId;
			}
		};

		this.connectionStateListener = (client, newState) -> {
			withStateLock(() -> {
				if (newState == ConnectionState.LOST) {
					stopWorkIterationRequested = true;
					if (disconnectedTimestamp == -1) {
						disconnectedTimestamp = System.currentTimeMillis();
					}
				} else if (newState == ConnectionState.SUSPENDED) {
					if (disconnectedTimestamp == -1) {
						disconnectedTimestamp = System.currentTimeMillis();
					}
				} else if (newState == ConnectionState.RECONNECTED) {
					if (!considerAsConnected()) {
						stopWorkIterationRequested = true;
					}
					disconnectedTimestamp = -1;
				}
				stateCondition.signalAll();
			});
		};
	}

	public LeaderSelector setTickDuration(Duration tickDuration) {
		this.tickDuration = tickDuration;
		return this;
	}

	public LeaderSelector setLeaderGuaranteeCheckInterval(Duration leaderGuaranteeCheckInterval) {
		this.leaderGuaranteeCheckInterval = leaderGuaranteeCheckInterval;
		return this;
	}

	public LeaderSelector setConnectionLossConsideredTicks(Duration connectionLossConfirmedDuration) {
		this.connectionLossConfirmedDuration = connectionLossConfirmedDuration;
		return this;
	}

	public LeaderSelector setNumberOfWorkIterationsUntilStop(int workIterationsUntilStop) {
		this.workIterationsUntilStop = workIterationsUntilStop;
		return this;
	}

	public LeaderSelector setMinimumWorkTime(Duration minimumWorkTime) {
		this.minimumWorkTime = minimumWorkTime;
		return this;
	}

	@Override
	public void start() {
		curatorFramework.getConnectionStateListenable().addListener(this.connectionStateListener, executorService);

		executorService.submit(() -> {
			while (!stopRequested) {
				if (disconnectedTimestamp != -1) {
					sleep(tickDuration.toMillis());
				} else {
					while (!stopRequested) {
						long timeToSleepMs = lastWorkTryingTimeMs == -1 ? -1 :
								lastWorkTryingTimeMs - System.currentTimeMillis() + minimumWorkTime.toMillis();
						if (timeToSleepMs > 0) {
							sleep(tickDuration.toMillis());
						} else {
							break;
						}
					}
					lastWorkTryingTimeMs = System.currentTimeMillis();
					tryToWork();
				}
			}
		});
	}

	@Override
	public CompletableFuture stop() {
		if (stopFuture != null) {
			return stopFuture;
		}

		curatorFramework.getConnectionStateListenable().removeListener(this.connectionStateListener);

		return withStateLock(() -> {
			stopFuture = new CompletableFuture<>();
			stopRequested = true;

			if (!working) {
				stopFuture.complete(null);
			}

			stateCondition.signalAll();

			return stopFuture;
		});
	}

	@Override
	public boolean isWorking() {
		return withStateLock(() -> !working);
	}

	private void tryToWork() {
		boolean lockAcquired = false;
		try {
			while (!lockAcquired) {
				if (stopRequested || disconnectedTimestamp != -1) {
					return;
				}
				try {
					lockAcquired = mutex.acquire(tickDuration.toMillis(), TimeUnit.MILLISECONDS);
				} catch (Throwable t) {
					log.error("Trying to acquire mutex failed.", t);
					sleep(tickDuration.toMillis());
				}
			}
			boolean doWork = lockAcquired && withStateLock(() -> {
				if (stopRequested || !considerAsConnected()) {
					return false;
				}
				working = true;
				stopWorkIterationRequested = false;
				return true;
			});
			if (doWork) {
				lastLeaderhipGuaranteeTestTime = System.currentTimeMillis();
				lastLeadershipGuranteeTestResult = true;
				workIterationsDone++;

				try {
					leader.work(new Leader.LeadershipState() {
						@Override
						public boolean shouldStop() {
							return stopRequested || stopWorkIterationRequested || !considerAsConnected() || !isNodeStillTheLeader();
						}
						@Override
						public void waitUntilShouldStop() {
							while (true){
								if (waitUntilShouldStopOrStateChanges()){
									return;
								}
							}
						}
						@Override
						public boolean waitUntilShouldStopOrStateChanges() {
							return withStateLock(()->{
								if (shouldStop()){
									return true;
								}
								waitForStateChange();
								return shouldStop();
							});
						}
					});
				} finally {
					withStateLock(() -> {
						working = false;
						if (stopFuture != null) {
							stopFuture.complete(null);
						}
						stateCondition.signalAll();
					});
				}
			}
		} finally {
			if (lockAcquired) {
				try {
					mutex.release();
				} catch (Throwable t) {
					log.error("Releasing mutex failed.", t);
				}
			}
			if (workIterationsUntilStop != -1 && workIterationsDone >= workIterationsUntilStop) {
				stop();
			}
		}
	}

	private boolean considerAsConnected() {
		return disconnectedTimestamp == -1 || disconnectedTimestamp + connectionLossConfirmedDuration.toMillis() > System.currentTimeMillis();
	}

	private boolean isNodeStillTheLeader() {
		if (lastLeaderhipGuaranteeTestTime == 0 || System.currentTimeMillis() > lastLeaderhipGuaranteeTestTime + leaderGuaranteeCheckInterval.toMillis()) {
			try {
				byte[] currentLeaderId = fetchCurrentLeaderId();

				lastLeadershipGuranteeTestResult = Arrays.equals(currentLeaderId, nodeId);
				if (!lastLeadershipGuranteeTestResult) {
					String currentLeaderIdSt = new String(currentLeaderId, "UTF-8");
					log.error("We have somehow lost leadership to a node with id '" + currentLeaderIdSt + "'.");
				}
			} catch (Throwable t) {
				lastLeadershipGuranteeTestResult = false;
				log.error("Trying to acquire mutex failed.", t);
			} finally {
				lastLeaderhipGuaranteeTestTime = System.currentTimeMillis();
			}
		}
		return lastLeadershipGuranteeTestResult;
	}

	private void withStateLock(Runnable runnable) {
		stateLock.lock();
		try {
			runnable.run();
		} finally {
			stateLock.unlock();
		}
	}

	private <T> T withStateLock(Callable<T> callable) {
		stateLock.lock();
		try {
			return callUnchecked(() -> callable.call());
		} finally {
			stateLock.unlock();
		}
	}

	private void waitForStateChange(){
		ExceptionUtils.runUnchecked(()->stateCondition.await(tickDuration.toMillis(), TimeUnit.MILLISECONDS));
	}

	private void sleep(long ms){
		runUnchecked(() -> {
			Thread.sleep(ms);
		});
	}

	protected byte[] fetchCurrentLeaderId() {
		Collection<String> participantNodes = ExceptionUtils.callUnchecked(() -> mutex.getParticipantNodes());
		if (participantNodes.size() > 0) {
			Iterator<String> iter = participantNodes.iterator();
			while (iter.hasNext()) {
				String path = iter.next();
				byte[] id = idForPath(path);

				if (id != null) {
					return id;
				}
			}
		}

		return null;
	}

	private byte[] idForPath(String path) {
		try {
			return curatorFramework.getData().forPath(path);
		} catch (KeeperException.NoNodeException e) {
			return null;
		} catch (Exception e) {
			throw ExceptionUtils.toUnchecked(e);
		}
	}
}
