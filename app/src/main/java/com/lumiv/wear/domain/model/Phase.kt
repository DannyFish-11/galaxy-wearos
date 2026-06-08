package com.lumiv.wear.domain.model

/**
 * Three-phase lifecycle state machine (matches Desktop/Android).
 *
 * LOW-FIX: Moved from LumivWearApplication.kt to domain/model/ layer
 * to follow clean architecture principles. Phase is a core domain concept
 * and should not be co-located with application infrastructure code.
 *
 * - SILENT:   No active connection or background monitoring only
 * - LIMINAL:  WebSocket connected, authentication pending/liminal state
 * - MANIFEST: Fully authenticated and operational — Lumiv features are "manifest"
 */
enum class Phase {
    SILENT,
    LIMINAL,
    MANIFEST
}
