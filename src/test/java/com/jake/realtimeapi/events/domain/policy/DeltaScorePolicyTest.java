package com.jake.realtimeapi.events.domain.policy;

import com.jake.realtimeapi.events.domain.exception.InvalidDeltaScoreException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeltaScorePolicyTest {

    private final DeltaScorePolicy policy = new DeltaScorePolicy();

    @Test
    void validate_allowsPositiveDelta() {
        assertDoesNotThrow(() -> policy.validate(1));
        assertDoesNotThrow(() -> policy.validate(100));
    }

    @Test
    void validate_throwsForZeroDelta() {
        InvalidDeltaScoreException exception = assertThrows(
                InvalidDeltaScoreException.class,
                () -> policy.validate(0)
        );

        assertEquals("Delta score must be >= 1, but was 0", exception.getMessage());
    }

    @Test
    void validate_throwsForNegativeDelta() {
        InvalidDeltaScoreException exception = assertThrows(
                InvalidDeltaScoreException.class,
                () -> policy.validate(-10)
        );

        assertEquals("Delta score must be >= 1, but was -10", exception.getMessage());
    }
}
