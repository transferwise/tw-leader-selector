package com.transferwise.common.leaderselector;

import java.time.Duration;

public interface Leader {
    /**
     * Leadership is reserved until you exit this method.
     * <p>
     * It is possible to loose the leadership by other reasons (e.g. network loss), so frequent consulting with the state object is essential.
     */
    void work(Control control);

    interface Control {
        /**
         * Returns true if the leadership may have been lost, or the leaderselector has been asked to stop. In all those cases
         * the work should be stopped and the method `work()` exited.
         */
        boolean shouldStop();

        /**
         * Waits until the leadership is going to be taken away or waitTime expires.
         */
        boolean waitUntilShouldStop(Duration waitTime);

        void workAsyncUntilShouldStop(Runnable startLogic, Runnable stopLogic);
    }
}
