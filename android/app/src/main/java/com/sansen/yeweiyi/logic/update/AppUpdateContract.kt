package com.sansen.yeweiyi.logic.update

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

enum class AppUpdateStatus {
    AVAILABLE, CURRENT, OLDER, NOT_ASSIGNED, DISABLED, INFO_PENDING, UNSUPPORTED_SCHEMA, INVALID_INFO
}

data class AppUpdateCandidate(
    val releaseId: String,
    val tbPackageId: String,
    val assignmentRevision: Long,
    val versionCode: Int,
    val versionName: String,
    val sizeBytes: Long,
    val sha256: String,
    val minSdk: Int,
    val supportedAbis: List<String>,
    val releaseNotes: Map<String, String>,
    val releasedAt: String
) {
    fun notes(locale: String): String = listOf(locale, locale.substringBefore('-'), "en", "zh")
        .mapNotNull { releaseNotes[it]?.takeIf(String::isNotEmpty) }.firstOrNull() ?: ""

    fun isCompatible(sdk: Int, deviceAbis: List<String>): Boolean =
        sdk >= minSdk && deviceAbis.any { it in supportedAbis }

    fun sameTarget(other: AppUpdateCandidate): Boolean =
        releaseId == other.releaseId && tbPackageId == other.tbPackageId &&
            assignmentRevision == other.assignmentRevision && versionCode == other.versionCode &&
            sizeBytes == other.sizeBytes && sha256 == other.sha256
}

data class AppUpdateCheck(val status: AppUpdateStatus, val candidate: AppUpdateCandidate? = null)

/** Only complete fresh Device API responses qualify; MQTT pushes invalidate cached candidates. */
object AppUpdateContract {
    const val TITLE = "com.sansen.yeweiyi"
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    const val MAX_INFO_BYTES = 64 * 1024
    const val SHARED_KEYS = "sw_title,sw_version,sw_size,sw_checksum_algorithm,sw_checksum,appUpdateManifest"
    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val hashPattern = Regex("[0-9a-fA-F]{64}")
    private val integerPattern = Regex("0|[1-9][0-9]*")
    private val localePattern = Regex("[a-z]{2}(-[A-Z]{2})?")
    private val abiNames = setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64")

    @JvmStatic
    fun parse(response: String, currentVersionCode: Int): AppUpdateCheck {
        if (currentVersionCode < 0 || response.length > MAX_INFO_BYTES || response.toByteArray(Charsets.UTF_8).size > MAX_INFO_BYTES) {
            return AppUpdateCheck(AppUpdateStatus.INVALID_INFO)
        }
        return try {
            val root = JsonReader(StringReader(response)).use { reader ->
                reader.isLenient = false
                val value = readValue(reader, 1)
                require(reader.peek() == JsonToken.END_DOCUMENT)
                value.asJsonObject
            }
            val shared = root.get("shared")?.takeIf { it.isJsonObject }?.asJsonObject ?: return invalid()
            if (SHARED_KEYS.split(',').none { shared.has(it) }) return AppUpdateCheck(AppUpdateStatus.NOT_ASSIGNED)
            val manifestValue = shared.get("appUpdateManifest")
            if (manifestValue == null || manifestValue.isJsonNull) return pending()
            val manifest = manifestValue.asJsonObject
            val schema = number(manifest, "schemaVersion", 1, Int.MAX_VALUE.toLong()).toInt()
            if (schema != 1) return AppUpdateCheck(AppUpdateStatus.UNSUPPORTED_SCHEMA)
            val enabled = manifest.get("enabled")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?: return invalid()
            if (!enabled.asBoolean) return AppUpdateCheck(AppUpdateStatus.DISABLED)
            require(text(manifest, "title") == TITLE && text(manifest, "packageName") == TITLE)
            val releaseId = text(manifest, "releaseId").also { require(idPattern.matches(it)) }
            val tbPackageId = text(manifest, "tbPackageId").also { require(uuidPattern.matches(it)) }
            val revision = number(manifest, "assignmentRevision", 1, 9007199254740991)
            val code = number(manifest, "versionCode", 1, Int.MAX_VALUE.toLong()).toInt()
            val name = text(manifest, "versionName").also { require(it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 128 && it == it.trim()) }
            val size = number(manifest, "sizeBytes", 1, MAX_APK_BYTES)
            val hash = text(manifest, "sha256").also { require(hashPattern.matches(it)) }.lowercase(Locale.US)
            val minSdk = number(manifest, "minSdk", 1, 1000).toInt()
            val abis = manifest.get("supportedAbis").asJsonArray.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString.also { abi -> require(abi in abiNames) }
            }.also { require(it.size in 1..8 && it.toSet().size == it.size) }
            val notes = manifest.get("releaseNotes").asJsonObject.entrySet().associate { (locale, value) ->
                require(localePattern.matches(locale) && value.isJsonPrimitive && value.asJsonPrimitive.isString)
                locale to value.asString
            }.also { require(it.size <= 16 && it.values.sumOf { note -> note.toByteArray(Charsets.UTF_8).size } <= 16384) }
            val releasedAt = text(manifest, "releasedAt").also { require(validTimestamp(it)) }
            for (key in listOf("sw_title", "sw_version", "sw_size", "sw_checksum_algorithm", "sw_checksum")) {
                if (!shared.has(key) || shared.get(key).isJsonNull) return pending()
            }
            val version = text(shared, "sw_version")
            require(Regex("[1-9][0-9]{0,9}").matches(version))
            val standardCode = version.toInt()
            val standardHash = text(shared, "sw_checksum").also { require(hashPattern.matches(it)) }
            require(text(shared, "sw_checksum_algorithm") in setOf("SHA256", "SHA-256"))
            val standardSize = number(shared, "sw_size", 1, MAX_APK_BYTES)
            if (text(shared, "sw_title") != TITLE || standardCode != code || standardSize != size || !standardHash.equals(hash, true)) return pending()
            val candidate = AppUpdateCandidate(releaseId, tbPackageId, revision, code, name, size, hash, minSdk, abis, notes, releasedAt)
            AppUpdateCheck(when {
                code > currentVersionCode -> AppUpdateStatus.AVAILABLE
                code == currentVersionCode -> AppUpdateStatus.CURRENT
                else -> AppUpdateStatus.OLDER
            }, candidate)
        } catch (_: RuntimeException) {
            invalid()
        } catch (_: IOException) {
            invalid()
        }
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            require(depth <= 32)
            val obj = JsonObject()
            reader.beginObject()
            while (reader.hasNext()) obj.add(reader.nextName(), readValue(reader, depth + 1))
            reader.endObject()
            obj
        }
        JsonToken.BEGIN_ARRAY -> {
            require(depth <= 32)
            val array = JsonArray()
            reader.beginArray()
            while (reader.hasNext()) array.add(readValue(reader, depth + 1))
            reader.endArray()
            array
        }
        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonParser().parse(reader.nextString())
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
        else -> throw IllegalArgumentException("INVALID_JSON")
    }

    private fun text(obj: JsonObject, key: String): String {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    private fun number(obj: JsonObject, key: String, min: Long, max: Long): Long {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber && integerPattern.matches(value.toString()))
        return value.asString.toLong().also { require(it in min..max) }
    }

    private fun validTimestamp(value: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})").matches(value)) return false
        return try {
            val normalized = if (value.endsWith("Z")) value.dropLast(1) + "+0000" else value.dropLast(3) + value.takeLast(2)
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply { isLenient = false }.parse(normalized) != null
        } catch (_: java.text.ParseException) { false }
    }

    private fun invalid() = AppUpdateCheck(AppUpdateStatus.INVALID_INFO)
    private fun pending() = AppUpdateCheck(AppUpdateStatus.INFO_PENDING)
}
