package com.jake.realtimeapi.snapshots.application;

import com.jake.realtimeapi.snapshots.application.model.SnapshotStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SnapshotStatusTracker {

    private static final long NO_SUCCESS_LAG_SECONDS = -1L;

    // 마지막 성공 시각은 worker와 internal API가 함께 읽고 쓰므로 공유 컴포넌트로 분리한다.
    private final AtomicReference<Instant> lastSuccessfulSnapshotAt = new AtomicReference<>();

    public void recordSuccess(Instant snapshotAt) {
        // snapshot 저장이 성공한 시점을 기록한다.
        lastSuccessfulSnapshotAt.set(snapshotAt);
    }

    public SnapshotStatus currentStatus() {
        // 내부 API 응답용 상태 모델을 조합한다.
        Instant lastSuccess = lastSuccessfulSnapshotAt.get();
        return new SnapshotStatus(lastSuccess, currentLagSeconds());
    }

    public double snapshotLagSeconds() {
        // Micrometer Gauge는 double supplier를 받으므로 메트릭용으로 노출한다.
        return currentLagSeconds();
    }

    private long currentLagSeconds() {
        Instant lastSuccess = lastSuccessfulSnapshotAt.get();
        if (lastSuccess == null) {
            // 아직 한 번도 성공하지 않았음을 -1로 표현한다.
            return NO_SUCCESS_LAG_SECONDS;
        }

        long now = Instant.now().getEpochSecond();
        long last = lastSuccess.getEpochSecond();
        return Math.max(0, now - last);
    }
}
