package com.sketchbook.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Multi-user-ready ACL row. v1 always emits empty collaborator lists (single-user); v1.2 fills
 * in real entries. Committed now so the registry wire format is stable across the v1 → v1.2
 * transition — see `docs/plans/2026-05-07-backend-generalization-design.md` §"TreeRegistry shape".
 */
@Serializable
data class Collaborator(@SerialName("user_id") val userId: UserId, @SerialName("role") val role: CollabRole)

@Serializable
enum class CollabRole {
    @SerialName("read")
    Read,

    @SerialName("write")
    Write,

    @SerialName("admin")
    Admin,
}
