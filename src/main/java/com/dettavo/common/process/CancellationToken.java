package com.dettavo.common.process;

import java.util.concurrent.atomic.AtomicBoolean;

/** Set once by whoever wants a running job stopped; checked by the process runner. */
public class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** For runs nobody can cancel, such as tool checks at startup. */
    public static CancellationToken none() {
        return new CancellationToken();
    }
}
