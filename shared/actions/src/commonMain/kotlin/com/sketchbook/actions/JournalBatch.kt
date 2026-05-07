package com.sketchbook.actions

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One batch of actions, written as a single JSON file under `data/journal/<batch_id>.json`.
 * Wire-stable with v0.1 Python: `{"batch_id": ..., "actor": ..., "actions": [...]}`.
 */
@Serializable
data class JournalBatch(
    val batch_id: String,
    val actor: String,
    val actions: List<ActionRecord>,
)

@OptIn(ExperimentalSerializationApi::class)
object JournalJson {
    /**
     * Pretty-printed (2-space indent) so files written by the Kotlin and Python implementations
     * compare byte-for-byte. `encodeDefaults` is on so optional `noop`/`hash_before`/etc.
     * always render.
     */
    val pretty: Json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

    val compact: Json =
        Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }
}
