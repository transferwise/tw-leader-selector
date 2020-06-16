package com.transferwise.common.leaderselector;

import com.transferwise.common.leaderselector.SharedReentrantLock.Builder;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.beans.factory.annotation.Autowired;

public class DefaultSharedReentrantLockBuilderFactory implements SharedReentrantLockBuilderFactory {

  @Autowired
  private CuratorFramework curatorFramework;

  @Autowired
  private LostCountingConnectionStateListener lostCountingConnectionStateListener;

  @Override
  public Builder createBuilder(String lockPath) {
    return new Builder().setCuratorFramework(curatorFramework).setLostCountingConnectionStateListener(lostCountingConnectionStateListener)
        .setLockPath(lockPath);
  }
}
