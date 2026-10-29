package com.citycopilot.http;

import java.io.IOException;

/** The shared downstream capacity was full, so the request was never sent and its outcome is known: not executed. */
public final class CapacityExhaustedException extends IOException {
    CapacityExhaustedException(String authority) {
        super("HTTP capacity exhausted for " + authority + "; no request was sent");
    }

    /** RestClient wraps transport failures, so callers inspect the whole cause chain. */
    public static boolean isCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CapacityExhaustedException) return true;
        }
        return false;
    }
}
