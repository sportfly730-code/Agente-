package com.codex.agent.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Conversores Room para tipos compostos. Enums são convertidos automaticamente pelo Room (nome).
 */
class Converters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    @TypeConverter
    fun mapToJson(map: Map<String, String>?): String =
        if (map.isNullOrEmpty()) "{}"
        else runCatching { json.encodeToString(mapSerializer, map) }.getOrDefault("{}")

    @TypeConverter
    fun jsonToMap(raw: String?): Map<String, String> =
        if (raw.isNullOrBlank()) emptyMap()
        else runCatching { json.decodeFromString(mapSerializer, raw) }.getOrDefault(emptyMap())
}
