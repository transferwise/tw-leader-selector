package com.transferwise.common.leaderselector;

public interface SharedReentrantLockBuilderFactory {

  SharedReentrantLock.Builder createBuilder(String lockPath);
}
