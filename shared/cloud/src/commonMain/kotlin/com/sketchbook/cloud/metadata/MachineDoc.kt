package com.sketchbook.cloud.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Wire shape for `/users/{uid}/machines/{hostId}` — the per-host roster the design doc's
 * "Data model → Firestore" section lays out. Populated on app launch (each host writes its
 * own row) and refreshed on `last_seen_at` cadence.
 *
 * No `owner_user_id` field — the path's `{uid}` is the only enforcement Security Rules need
 * (`match /machines/{hostId} { allow write: if isOwner(uid) ... }`); there's no
 * collaborator-aware variant on machine docs.
 *
 * **Wire field naming.** Snake_case via [@SerialName] (M8) — consistent with [TreeDoc] and
 * [LockDoc], so the Firestore Admin SDK and rule predicates see one canonical shape.
 *
 * Phase 3 ships the shape and the store; wiring `last_seen_at` to a per-launch heartbeat is
 * a Phase 4 follow-up (see design doc §"Out of scope for Phase 3").
 */
@Serializable
data class MachineDoc(
    @SerialName("host_name") val hostName: String,
    @SerialName("os") val os: String,
    // TODO(machine-heartbeat): nothing writes this today. Follow-up: write on session start
    // + a launch-cadence heartbeat. Tracked in the design doc's "Out of scope for Phase 3"
    // section.
    @SerialName("last_seen_at") val lastSeenAt: Instant,
    @SerialName("binary_version") val binaryVersion: String = "",
)
