package com.jake.realtimeapi.snapshots.application;

import com.jake.realtimeapi.snapshots.application.model.SnapshotStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SnapshotStatusTracker {

    private static final long NO_SUCCESS_LAG_SECONDS = -1L;

    private final AtomicReference<Instant> lastSuccessfulSnapshotAt = new AtomicReference<>();

    public void recordSuccess(Instant snapshotAt) {
        lastSuccessfulSnapshotAt.set(snapshotAt);
    }

    public SnapshotStatus currentStatus() {
        Instant lastSuccess = lastSuccessfulSnapshotAt.get();
        return new SnapshotStatus(lastSuccess, currentLagSeconds());
    }

    public double snapshotLagSeconds() {
        return currentLagSeconds();
    }

    private long currentLagSeconds() {
        Instant lastSuccess = lastSuccessfulSnapshotAt.get();
        if (lastSuccess == null) {
            return NO_SUCCESS_LAG_SECONDS;
        }

        long now = Instant.now().getEpochSecond();
        long last = lastSuccess.getEpochSecond();
        return Math.max(0, now - last);
    }
}
