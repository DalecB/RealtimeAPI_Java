package com.jake.realtimeapi.snapshots.domain.exception;

public class SnapshotNotFoundException extends RuntimeException {

    public SnapshotNotFoundException(String message) {
        super(message);
    }
}
