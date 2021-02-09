package com.transferwise.common.leaderselector;

import java.time.Duration;

/**
 * A distributed lock.
 * 
 * <p>When the lock is acquired, the caller has to make sure a `release()` method gets called later on.
 * 
 * <code>
 * if (lock.acquire(...)){
 *   try{
 *     ...   
 *   }
 *   finally{
 *    lock.release();
 *   }
 * }
 * </code>
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public interface ILock {

  /**
   * Tries to acquire the lock in specified time window.
   *
   * @return `true` if lock was acquired.
   */
  boolean acquire(Duration maxWait);

  /**
   * Release the lock.
   */
  void release();

  /**
   * Returns true only when we are absolutely sure, we still own a lock and no other process has acquired it.
   * 
   * <p>In some case also waits up to `maxWait` until we will become an owner.
   */
  boolean isOwned(Duration maxWait);

  /**
   * Throws IllegalStateException when lock is not active or will not become active in maxWait time.
   */
  void verifyOwnership(Duration maxWait) throws IllegalStateException;

  /**
   * Returns true only when we are absolutely sure, we still own a lock and no other process has acquired it.
   */
  boolean considerAsOwned();

  /**
   * Returns the Zookeeper path for this lock.
   */
  String getPath();
}
