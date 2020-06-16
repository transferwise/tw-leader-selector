package com.transferwise.common.leaderselector;

import java.time.Duration;

@SuppressWarnings({"WeakerAccess", "unused"})
public interface ILock {

  boolean acquire(Duration maxWait);

  void release();

  boolean isOwned(Duration maxWait);

  /**
   * Throws IllegalStateException when lock is not active or will not become active in maxWait time.
   */
  void verifyOwnership(Duration maxWait) throws IllegalStateException;

  boolean considerAsOwned();

  String getPath();
}
