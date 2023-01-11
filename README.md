# tw-leader-selector

![Apache 2](https://img.shields.io/hexpm/l/plug.svg)
![Java 11](https://img.shields.io/badge/Java-11-blue.svg)
![Maven Central](https://badgen.net/maven/v/maven-central/com.transferwise.common/tw-leader-selector)
[![Owners](https://img.shields.io/badge/team-AppEng-blueviolet.svg?logo=wise)](https://transferwise.atlassian.net/wiki/spaces/EKB/pages/2520812116/Application+Engineering+Team) [![Slack](https://img.shields.io/badge/slack-sre--guild-blue.svg?logo=slack)](https://app.slack.com/client/T026FB76G/CLR1U8SNS)
> Use the `@application-engineering-on-call` handle on Slack for help.
---
Provides a proper leader selector recipe with following benefits compared to Curator's recipe.

- Not based on Thread interruptions (very bad and old practice), so the control over the selector is completely graceful. Additional benefit from it
  is, that it is very quiet on graceful shutdown.
- Better model for most use cases, especially for "periodic background jobs". Also. for use cases where halting and resuming of some work is needed.
- Much easier for a developer to use for full consistency, no need to implement nor even know about connection state listeners.
- Non-blocking on all steps.
- More robust and reliable. Has additional built-in assertions against network partitioning, including checking the actual leader's node data and
  comparing to our (assumed) leader's id.

## How to use

### Setup

First we need to add dependencies:

```groovy
implementation "com.transferwise.common:tw-leader-selector"
runtimeOnly "com.transferwise.common:tw-leader-selector-starter"
```

Then configure the Zookeeper address:

```yaml
tw-curator.zookeeper-connect-string: ${ENV_ZOOKEEPER_CONNECT_STRING}
```

Libraries depending on tw-leader-selector might have use cases in which leader selection is not required and they want
to avoid introducing the Zookeeper dependency. In that the service should not define the property 
`tw-curator.zookeeper-connect-string`. Not defining the Zookeeper address is enough, but for explicitness, one can 
define the following property instead:

```yaml
tw-curator.disabled: true
```

### Distributed lock

Even when the library is named as "leader selector", it can be used just for distributed locking as well.
> Actually the leader selector is just a fancy implementation around a distributed lock.

```java
@Autowired
private SharedReentrantLockBuilderFactory sharedReentrantLockBuilderFactory;

ILock lock = sharedReentrantLockBuilderFactory.createBuilder("/tw/my/shared/unique/lock/path").build();

boolean acquired = lock.acquire(Duration.ofSeconds(10));
if (acquired) {
  try {
    doSomeNotThreadSafeMagicAlone(lock);
  } finally{
    lock.release();
  }
} else{
  log.error("Lock was not acquired in time. Should throw some mad exception here.");
}
```

Notice, that in case of Zookeeper we are dealing with networked distributed system, where lots of things can go wrong. The simplest case imagined can
be that network connection drops.

Now if our code just keeps executing without knowing this has happened, we will easily end up having multiple nodes executing the same code at the
same time. Usually the Zookeeper's session timeout is set to its maximum possible value of 40s, which means, that usually, after 40s from a network
disruption, the Zookeeper cluster is giving the lock away.

So, for any long taking process, we need to periodically check if we are still absolutely sure that we do own that lock. And, if that is not the case
any more we should stop the work and in case of database related workloads, rollback the transaction.

```java
@Transactional
void doSomeNotThreadSafeMagicAlone(ILock lock){
    List<Book> books = fetchMillionBooksFromDatabase();
    for(Book book : books) {
      processBook(book);
      if (!lock.considerAsOwned) {
        throw new IllegalStateException("We somehow lost the lock. Let's rollback the transaction.")
      }
    }
}
```

> Once more, using this library alone does not guarantee single threaded execution. You need to understand the failure scenarios
> and periodically check/ask from the library if one has happened.

### Leader Selector

Leader selector use cases usually start with creating an `ILock` instance like shown above. Leader selector itself can be created by a builder.

```java
var leaderSelector = new LeaderSelectorV2.Builder().setLock(lock).setExecutorService(executorService).leaderSelector(aLeader).build();
```

A simple use case where you just want to run some short running code under a leader selector:

```java
var leaderSelector = new LeaderSelectorV2.Builder().setLock(lock).setExecutorService(executorService).leaderSelector(
    control -> {
      log.info("I'm now the leader and will do some work.");
    })
.build();

leaderSelector.start();
```

> Notice, that you need to start the leader selector in order it to start doing anything useful.

Leader selector will keep executing that code repeatedly, until it is stopped.
> It is always adviced to stop the leader selector during a graceful shutdown, so we don't create any errors and noise when application finally closes.
> You may want to turn towards [tw-graceful-shutdown library](https://github.com/transferwise/tw-graceful-shutdown), which makes it easy.
```java
leaderSelector.stop();
```

Notice the `com.transferwise.common.leaderselector.Leader.Control` parameter given to the leader, this is quite important and allows the leader code
to check and control the leadership status by itself.
> Please consult with its [javadoc](tw-leader-selector/src/main/java/com/transferwise/common/leaderselector/Leader.java) how and when it can be used.

As mentioned in the "Distributed locking" paragraph, we need to periodically verify, if we still are the leader or not. If we are not, we need to
stop all the work and also rollback any ongoing database transactions.

For asking that state, you can use the same `control` parameter, namely `control.shouldStop()` method.

```java
Leader leader = new Leader(control -> {
    while(!control.shouldStop()) {
      doSomeImportantButQuickWork(control);
    }
});
```

Some more advanced case could be a complex asynchronous work being done under the leader selector, for example 
(tw-tasks)[https://github.com/transferwise/tw-tasks-executor] task cleaner process looks like following.

```java

MutableObject<ScheduledTaskExecutor.TaskHandle> taskHandleHolder = new MutableObject<>();
Leader leader = new Leader(control -> {
  control.workAsyncUntilShouldStop(
  () -> {
    taskHandleHolder.setValue(scheduledTaskExecutor.scheduleAtFixedInterval(this::deleteFinishedOldTasks, Duration.ofMinutes(1),
      Duration.ofMinutes(1));
  },
  () -> {
    if (taskHandleHolder.getValue() != null) {
      taskHandleHolder.getValue().stop();
      taskHandleHolder.getValue().waitUntilStopped(Duration.ofMinutes(1));
    }
    log.info("Tasks cleaner stopped.");
  });
});
```

## License

Copyright 2021 TransferWise Ltd.

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy
of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under
the License.
