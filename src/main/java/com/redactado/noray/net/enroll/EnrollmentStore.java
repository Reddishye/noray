package com.redactado.noray.net.enroll;

import com.redactado.noray.net.NodeId;

import java.util.List;
import java.util.Optional;

public interface EnrollmentStore {
    void save(EnrollmentRecord record);
    void delete(NodeId nodeId);
    Optional<EnrollmentRecord> find(NodeId nodeId);
    List<EnrollmentRecord> list();
}