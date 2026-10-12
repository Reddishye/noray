package com.redactado.noray.web.server;

import java.util.LinkedHashMap;
import java.util.Map;

/** Exponential back-off per client address after failed logins: 1 s, 2 s, 4 s ... capped at 5 minutes. */
final class LoginThrottle {

    private record State(int failures, long blockedUntilMillis) {
    }

    private static final int MAX_TRACKED = 10_000;
    private static final long CAP_MILLIS = 5 * 60_000;

    private final Map<String, State> states = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, State> e) {
            return size() > MAX_TRACKED;
        }
    };

    /** Milliseconds the client must still wait, or 0. */
    synchronized long waitMillis(String client, long now) {
        State s = states.get(client);
        return s == null ? 0 : Math.max(0, s.blockedUntilMillis - now);
    }

    synchronized void failed(String client, long now) {
        State s = states.get(client);
        int f = s == null ? 1 : s.failures + 1;
        long delay = Math.min(CAP_MILLIS, 1000L << Math.min(f - 1, 20));
        states.put(client, new State(f, now + delay));
    }

    synchronized void succeeded(String client) {
        states.remove(client);
    }
}
