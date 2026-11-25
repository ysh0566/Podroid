package com.excp.podroid.hostservices;

import java.util.concurrent.atomic.AtomicInteger;

/** TCP tunnels and UDP associations consume the same admission budget. */
final class RelayBudget {
    final int limit;
    private final AtomicInteger used = new AtomicInteger();
    RelayBudget(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive limit required");
        this.limit = limit;
    }
    boolean acquire() {
        int value;
        do {
            value = used.get();
            if (value >= limit) return false;
        } while (!used.compareAndSet(value, value + 1));
        return true;
    }
    void release() { used.decrementAndGet(); }
}
