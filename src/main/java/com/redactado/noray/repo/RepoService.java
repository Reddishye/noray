package com.redactado.noray.repo;

import com.redactado.noray.filesystem.api.FsException;
import com.redactado.noray.filesystem.store.ObjectId;
import java.util.List;
import java.util.Optional;

public interface RepoService {
    void updateRef(RefName name, ObjectId expectedOrNull, ObjectId newTarget) throws FsException;
    Optional<RefPointer> readRef(RefName name);
    List<RefPointer> listRefs();
}
