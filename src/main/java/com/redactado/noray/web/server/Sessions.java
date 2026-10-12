package com.redactado.noray.web.server;

import com.redactado.noray.web.ui.Blob;
import com.redactado.noray.web.ui.PageView;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Signed-in browser sessions, each with its open pages and blob URLs, all bounded. */
final class Sessions {

    static final int MAX_SESSIONS = 64;
    static final int MAX_PAGES = 16;
    static final int MAX_BLOBS = 512;
    static final int MAX_STREAMS = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Session {
        final String id = random(32);
        final String csrf = random(24);
        final String user;
        final long createdNanos = System.nanoTime();
        volatile long lastSeenNanos = createdNanos;
        final AtomicInteger streams = new AtomicInteger();
        private final Map<String, PageView> pages = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PageView> e) {
                return size() > MAX_PAGES;
            }
        };
        private final Map<String, BlobEntry> blobs = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BlobEntry> e) {
                return size() > MAX_BLOBS;
            }
        };

        Session(String user) {
            this.user = user;
        }

        /** Operators run any action; viewers only view actions. */
        boolean operator() {
            return user.startsWith("operator:");
        }

        synchronized void addPage(PageView p) {
            pages.put(p.id(), p);
        }

        synchronized Optional<PageView> page(String id) {
            return Optional.ofNullable(pages.get(id));
        }

        synchronized List<PageView> pageList() {
            return List.copyOf(pages.values());
        }

        synchronized String addBlob(Blob blob) {
            String t = random(16);
            blobs.put(t, new BlobEntry(blob, System.nanoTime()));
            return t;
        }

        synchronized Optional<Blob> blob(String token, Duration ttl) {
            BlobEntry e = blobs.get(token);
            if (e == null || System.nanoTime() - e.createdNanos > ttl.toNanos()) return Optional.empty();
            return Optional.of(e.blob);
        }

        synchronized void dropIdlePages(Duration idle) {
            pages.values().removeIf(p -> p.idle().compareTo(idle) > 0);
        }
    }

    record BlobEntry(Blob blob, long createdNanos) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Duration idle;
    private final Duration lifetime;

    Sessions(Duration idle, Duration lifetime) {
        this.idle = idle;
        this.lifetime = lifetime;
    }

    Session create(String user) {
        sweep();
        if (sessions.size() >= MAX_SESSIONS) {
            sessions.values().stream().min(Comparator.comparingLong(s -> s.lastSeenNanos))
                    .ifPresent(s -> sessions.remove(s.id));
        }
        Session s = new Session(user);
        sessions.put(s.id, s);
        return s;
    }

    Optional<Session> get(String id) {
        if (id == null) return Optional.empty();
        Session s = sessions.get(id);
        if (s == null) return Optional.empty();
        long now = System.nanoTime();
        if (now - s.lastSeenNanos > idle.toNanos() || now - s.createdNanos > lifetime.toNanos()) {
            sessions.remove(id);
            return Optional.empty();
        }
        s.lastSeenNanos = now;
        return Optional.of(s);
    }

    void remove(String id) {
        if (id != null) sessions.remove(id);
    }

    int size() {
        return sessions.size();
    }

    void sweep() {
        long now = System.nanoTime();
        for (Session s : new ArrayList<>(sessions.values())) {
            if (now - s.lastSeenNanos > idle.toNanos() || now - s.createdNanos > lifetime.toNanos()) {
                sessions.remove(s.id);
            } else {
                s.dropIdlePages(Duration.ofMinutes(10));
            }
        }
    }

    List<Session> all() {
        return List.copyOf(sessions.values());
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
