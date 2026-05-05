package com.sketchbook.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parsed Google Cloud service-account JSON. Only the fields we use are declared so unrecognized
 * fields fail loudly during deserialization (`ignoreUnknownKeys = false` is the safer default
 * for credentials).
 */
@Serializable
data class ServiceAccountKey(
    @SerialName("type") val type: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("private_key_id") val privateKeyId: String,
    @SerialName("private_key") val privateKeyPem: String,
    @SerialName("client_email") val clientEmail: String,
    @SerialName("token_uri") val tokenUri: String = "https://oauth2.googleapis.com/token",
)
