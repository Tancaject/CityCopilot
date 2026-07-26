package com.citycopilot.agent.confirmation;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class PlanCheckpointIntegrity {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private PlanCheckpointIntegrity() {
    }

    public static String hash(PlanCheckpoint checkpoint) {
        try {
            byte[] canonical = MAPPER.writeValueAsBytes(checkpoint);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash plan checkpoint", error);
        }
    }

    public static boolean valid(PendingAction action) {
        if (action == null || action.planHash() == null || action.planHash().isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                action.planHash().getBytes(StandardCharsets.UTF_8),
                hash(toCheckpoint(action)).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static PlanCheckpoint toCheckpoint(PendingAction action) {
        return new PlanCheckpoint(
                action.planVersion(),
                action.originalMessage(),
                action.hints(),
                action.steps(),
                action.currentStep(),
                action.completedOutputs(),
                action.evidenceIds(),
                action.verificationSteps()
        );
    }
}
