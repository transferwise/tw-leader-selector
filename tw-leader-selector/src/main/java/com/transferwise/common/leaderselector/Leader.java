package com.transferwise.common.leaderselector;

import java.time.Duration;

public interface Leader {

  /**
   * Leadership is reserved until you exit this method.
   *
   * <p>It is possible to loose the leadership by other reasons (e.g. network loss), so frequent consulting with the state object is essential.
   */
  void work(Control control);

  interface Control {

    /**
     * Returns true if the leadership may have been lost, or the leader selector has been asked to stop. In all those cases the work should be stopped
     * and the method `work()` exited.
     * 
     * <p>In case of database transactions they should be rolled back.
     */
    boolean shouldStop();

    /**
     * Waits until the leadership is going to be taken away or waitTime expires.
     */
    @SuppressWarnings("UnusedReturnValue")
    boolean waitUntilShouldStop(Duration waitTime);

    /**
     * Executes `startLogic`, then waits until we are not sure anymore we have the leadership; then runs `stopLogic`.
     * 
     * <p>Can be convenient for long running async processes, where `startLogic` starts a process and `stopLogic` stops it.
     */
    void workAsyncUntilShouldStop(Runnable startLogic, Runnable stopLogic);

    /**
     * If asynchronous code would want to indicate, that it is interested of giving away the leader.
     *
     * <p>For example in the case where {@code workAsyncUntilShouldStop} is used.
     */
    void yield();
  }
}
