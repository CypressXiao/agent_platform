package com.agentplatform.gateway.rag.chunking;

/**
 * Utility for building vector collection names using the standard pattern:
 * tenant_scene_profile_model. All segments are required.
 */
public final class CollectionNameFormatter {

    private CollectionNameFormatter() {
    }

    public static String format(String tenant, String scene, String profile, String model) {
        String sanitizedTenant = sanitizeRequired("tenant", tenant);
        String sanitizedScene = sanitizeRequired("scene", scene);
        String sanitizedProfile = sanitizeRequired("profile", profile);
        String sanitizedModel = sanitizeRequired("model", model);

        return String.format("%s_%s_%s_%s",
                sanitizedTenant,
                sanitizedScene,
                sanitizedProfile,
                sanitizedModel);
    }

    private static String sanitizeRequired(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required collection segment: " + fieldName);
        }
        String sanitized = value.toLowerCase()
                .replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");

        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Invalid value for collection segment: " + fieldName);
        }
        return sanitized;
    }
}
