package com.lulu.music.data.store

import android.content.Context
import android.content.SharedPreferences
import com.lulu.music.BeansApplication
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * SharedPreferences + kotlinx.serialization helper.
 *
 * The iOS app persists these collections in `UserDefaults` as JSON blobs. We keep the exact same
 * storage KEY STRINGS and the same "corrupt data degrades to empty, never throws" behaviour.
 */
object Prefs {

    private const val PREFS_NAME = "beans_prefs"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val prefs: SharedPreferences by lazy {
        BeansApplication.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun <T> readList(key: String, serializer: KSerializer<T>): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(serializer), raw) }
            .getOrDefault(emptyList())
    }

    fun <T> writeList(key: String, serializer: KSerializer<T>, value: List<T>) {
        runCatching { prefs.edit().putString(key, json.encodeToString(ListSerializer(serializer), value)).apply() }
    }

    fun readString(key: String, fallback: String = ""): String =
        prefs.getString(key, fallback) ?: fallback

    fun writeString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private val stringListSerializer: KSerializer<List<String>> = ListSerializer(String.serializer())

    fun readStringList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(stringListSerializer, raw) }.getOrDefault(emptyList())
    }

    fun writeStringList(key: String, value: List<String>) {
        runCatching {
            prefs.edit().putString(key, json.encodeToString(stringListSerializer, value)).apply()
        }
    }

    val stringMapSerializer: KSerializer<Map<String, String>> =
        MapSerializer(String.serializer(), String.serializer())

    /** 读一个 `String -> String` 的 JSON 对象；缺失 / 损坏一律降级为空 Map（绝不抛）。 */
    fun readStringMap(key: String): Map<String, String> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(stringMapSerializer, raw) }.getOrDefault(emptyMap())
    }

    fun writeStringMap(key: String, value: Map<String, String>) {
        runCatching {
            prefs.edit().putString(key, json.encodeToString(stringMapSerializer, value)).apply()
        }
    }
}
