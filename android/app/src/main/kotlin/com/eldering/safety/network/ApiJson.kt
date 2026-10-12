package com.eldering.safety.network

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * The one [Json] configuration for the Eldering API.
 *
 * - `ignoreUnknownKeys`: the server may add response fields at any time without breaking old app versions.
 * - `explicitNulls = false`: optional request fields that are null are omitted (the server treats absent and
 *   null the same everywhere except `PATCH /v1/me/settings`, which is built as a raw JsonObject for that reason),
 *   and absent nullable response fields (Go `omitempty`) decode as null.
 * - `coerceInputValues`: an unknown enum value in an optional response field falls back to the default.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * RFC 3339 timestamps as used by the Go server (`time.Time`): emitted in UTC with up to nanosecond fractions
 * (`2026-10-13T08:15:30.123Z`), accepted with any offset (`+05:30`) or `Z`.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.eldering.safety.network.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            // OffsetDateTime handles both "Z" and numeric offsets on every API level (ISO_INSTANT parsing of
            // offsets only arrived in Java 12).
            OffsetDateTime.parse(text).toInstant()
        } catch (e: DateTimeParseException) {
            throw SerializationException("Invalid RFC 3339 timestamp: '$text'", e)
        }
    }
}

/** [Instant] serialized as an RFC 3339 string; use this type in every wire model. */
typealias ApiInstant = @Serializable(with = InstantSerializer::class) Instant
