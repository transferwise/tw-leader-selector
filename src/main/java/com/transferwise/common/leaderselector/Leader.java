package com.transferwise.common.leaderselector;

public interface Leader {
    /**
     * Leadership is reserved until you exit this method.
     *
     * It is possible to loose the leadership by other reasons (e.g. network loss), so frequent consulting with the state object is essential.
     */
    void work(LeadershipState state);

    interface LeadershipState {
        /**
         * Returns true if the leadership may have been lost, or the leaderselector has been asked to stop. In all those cases
         * the work should be stopped and the method `work()` exited.
         */
        boolean shouldStop();
        /**
         * Waits indefinitely until the leadership is going to be taken away.
         */
        void waitUntilShouldStop();
        /**
         * Waits until leadership is going to be taken away, but not more than one "tick".
         *
         * Returns true, if leadership is going to be taken away.
         */
        boolean waitUntilShouldStopOrStateChanges();
    }
}
