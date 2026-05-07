package com.sketchbook.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The kind of [TrackedTree] — what shape of files it holds and which sync policies apply. The
 * registry stores this string-form so unknown kinds round-trip across binary versions.
 */
@Serializable(with = TrackedTreeKindSerializer::class)
sealed interface TrackedTreeKind {
    /** The wire-stable identifier emitted into manifests + registry entries. */
    val wireName: String

    data object Project : TrackedTreeKind {
        override val wireName: String = "project"
    }

    data object UserLibrary : TrackedTreeKind {
        override val wireName: String = "user_library"
    }

    /**
     * Kind we don't know how to interpret on this binary. Preserves the wire string so the
     * registry round-trips unchanged when an older binary reads a newer entry.
     */
    data class Unknown(override val wireName: String) : TrackedTreeKind

    companion object {
        fun fromWire(name: String): TrackedTreeKind = when (name) {
            Project.wireName -> Project
            UserLibrary.wireName -> UserLibrary
            else -> Unknown(name)
        }
    }
}

internal object TrackedTreeKindSerializer : KSerializer<TrackedTreeKind> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor

    override fun serialize(encoder: Encoder, value: TrackedTreeKind) {
        encoder.encodeString(value.wireName)
    }

    override fun deserialize(decoder: Decoder): TrackedTreeKind = TrackedTreeKind.fromWire(decoder.decodeString())
}
