package com.redactado.noray.repo;

import com.redactado.noray.filesystem.api.FsErrorCode;
import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ObjectId;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class DefaultRepoService implements RepoService {

    private final ConcurrentHashMap<RefName, RefPointer> refs = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void updateRef(RefName name, ObjectId expectedOrNull, ObjectId newTarget) throws FsException {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(newTarget, "newTarget");
        lock.lock();
        try {
            RefPointer current = refs.get(name);
            if (expectedOrNull == null) {
                if (current != null) {
                    throw new FsException(FsErrorCode.CONFLICT, "Ref already exists: " + name);
                }
                refs.put(name, new RefPointer(name, newTarget, 1));
                return;
            }
            if (current == null) {
                throw new FsException(code("NOT_FOUND", "NOTFOUND"), "Ref not found: " + name);
            }
            if (!current.target().equals(expectedOrNull)) {
                throw new FsException(FsErrorCode.CONFLICT, "Target mismatch for ref: " + name);
            }
            refs.put(name, new RefPointer(name, newTarget, current.generation() + 1));
        } finally {
            lock.unlock();
        }
    }

    private static FsErrorCode code(String a, String b) {
        try { return FsErrorCode.valueOf(a); }
        catch (IllegalArgumentException e) { return FsErrorCode.valueOf(b); }
    }

    @Override
    public Optional<RefPointer> readRef(RefName name) {
        return Optional.ofNullable(refs.get(name));
    }

    @Override
    public List<RefPointer> listRefs() {
        lock.lock();
        try {
            return refs.values().stream()
                    .sorted(Comparator.comparing(p -> p.name().value()))
                    .collect(Collectors.toList());
        } finally {
            lock.unlock();
        }
    }
}
