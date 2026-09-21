package com.lulu.music.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.lulu.music.data.prefs.SettingsStore

/**
 * Equalizer / bass-boost / virtualizer control, attached to ExoPlayer's audio session.
 *
 * Port of the iOS `BeansEqualizer`. iOS drives an `AVAudioUnitEQ` node; Android's equivalent is the
 * platform `android.media.audiofx` family, so the band model is read from the device rather than
 * hard-coded (band count and frequency centres differ per device).
 *
 * Every call is defensive: audio effects are frequently unavailable (no session yet, another app
 * holding the effect, emulators, some Bluetooth routes). None of these methods throw.
 */
object EqualizerController {

    private const val TAG = "BeansEqualizer"

    /** Named presets offered in the UI. `FLAT` is the neutral default. */
    enum class Preset(val key: String, val zh: String, val en: String) {
        FLAT("flat", "原声", "Flat"),
        POP("pop", "流行", "Pop"),
        ROCK("rock", "摇滚", "Rock"),
        JAZZ("jazz", "爵士", "Jazz"),
        CLASSICAL("classical", "古典", "Classical"),
        DANCE("dance", "舞曲", "Dance"),
        BASS("bass", "重低音", "Bass Boost"),
        TREBLE("treble", "高音增强", "Treble"),
        VOCAL("vocal", "人声", "Vocal");

        companion object {
            fun fromKey(key: String?): Preset = entries.firstOrNull { it.key == key } ?: FLAT
        }
    }

    data class Band(
        val index: Short,
        val centerFreqHz: Int,
        val minLevelMb: Short,
        val maxLevelMb: Short,
    )

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var sessionId: Int = 0

    @Volatile
    var isAvailable: Boolean = false
        private set

    /** Attach to a player audio session. Safe to call repeatedly; re-attaches when the session changes. */
    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == sessionId && isAvailable) return
        if (audioSessionId == sessionId && equalizer != null) return
        release()
        sessionId = audioSessionId
        try {
            equalizer = Equalizer(0, audioSessionId).also { it.enabled = false }
            bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
            virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
            isAvailable = true
            applyFromSettings()
        } catch (t: Throwable) {
            Log.w(TAG, "equalizer unavailable for session $audioSessionId: ${t.message}")
            isAvailable = false
            equalizer = null
            bassBoost = null
            virtualizer = null
        }
    }

    @Synchronized
    fun release() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.enabled = false }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.enabled = false }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        isAvailable = false
    }

    /** Device band layout, for drawing the sliders. Empty when unavailable. */
    @Synchronized
    fun bands(): List<Band> {
        val eq = equalizer ?: return emptyList()
        return runCatching {
            val range = eq.bandLevelRange
            (0 until eq.numberOfBands.toInt()).map { i ->
                val index = i.toShort()
                Band(
                    index = index,
                    centerFreqHz = eq.getCenterFreq(index),
                    minLevelMb = range[0],
                    maxLevelMb = range[1],
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Push the persisted settings into the live audio effects. */
    @Synchronized
    fun applyFromSettings() {
        val eq = equalizer ?: return
        val enabled = SettingsStore.eqEnabled.value
        runCatching {
            if (enabled) {
                val custom = parseBandGains(SettingsStore.eqBands.value)
                if (custom.isNotEmpty()) {
                    // Explicit per-band gains win over the named preset.
                    for ((index, level) in custom) {
                        runCatching { eq.setBandLevel(index, level) }
                    }
                } else {
                    applyPresetInternal(Preset.fromKey(SettingsStore.eqPreset.value))
                }
                // Loudness enhancer style presets also nudge bass/virtualizer.
                setBassBoostInternal(SettingsStore.bassBoost.value)
                setVirtualizerInternal(SettingsStore.virtualizer.value)
            }
            eq.enabled = enabled
        }.onFailure { Log.w(TAG, "applyFromSettings failed: ${it.message}") }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        SettingsStore.setEqEnabled(enabled)
        val eq = equalizer ?: return
        runCatching { eq.enabled = enabled }
        if (enabled) applyFromSettings()
    }

    @Synchronized
    fun usePreset(preset: Preset) {
        SettingsStore.setEqPreset(preset.key)
        // Choosing a preset clears any manual band gains.
        SettingsStore.setEqBands("")
        applyPresetInternal(preset)
    }

    private fun applyPresetInternal(preset: Preset) {
        val eq = equalizer ?: return
        runCatching {
            val index = presetIndexFor(preset)
            if (index >= 0) {
                eq.usePreset(index.toShort())
            } else {
                applyManualCurve(preset)
            }
        }.onFailure { Log.w(TAG, "preset ${preset.key} failed: ${it.message}") }
    }

    /**
     * Android's built-in preset list varies by device and does not cover every Beans preset,
     * so unknown names fall back to a hand-built curve in millibels.
     */
    private fun presetIndexFor(preset: Preset): Int {
        val eq = equalizer ?: return -1
        val wanted = when (preset) {
            Preset.FLAT -> listOf("normal", "flat")
            Preset.POP -> listOf("pop")
            Preset.ROCK -> listOf("rock")
            Preset.JAZZ -> listOf("jazz")
            Preset.CLASSICAL -> listOf("classical")
            Preset.DANCE -> listOf("dance")
            else -> emptyList()
        }
        if (wanted.isEmpty()) return -1
        for (p in 0 until eq.numberOfPresets.toInt()) {
            val name = runCatching { eq.getPresetName(p.toShort()) }.getOrDefault("").lowercase()
            if (wanted.any { name.contains(it) }) return p
        }
        return -1
    }

    /** Gentle tone curves for presets the device does not ship. Levels are in millibels. */
    private fun applyManualCurve(preset: Preset) {
        val eq = equalizer ?: return
        val count = eq.numberOfBands.toInt()
        if (count == 0) return
        val range = eq.bandLevelRange
        val min = range[0].toInt()
        val max = range[1].toInt()
        for (i in 0 until count) {
            val position = if (count == 1) 0.5f else i.toFloat() / (count - 1)
            val target = when (preset) {
                Preset.BASS -> when {
                    position < 0.34f -> 0.75f
                    position < 0.67f -> 0.15f
                    else -> -0.15f
                }
                Preset.TREBLE -> when {
                    position < 0.34f -> -0.2f
                    position < 0.67f -> 0.15f
                    else -> 0.7f
                }
                Preset.VOCAL -> when {
                    position < 0.25f -> -0.3f
                    position < 0.75f -> 0.55f
                    else -> 0.1f
                }
                else -> 0f
            }
            val level = (target * (if (target >= 0) max else -min)).toInt()
                .coerceIn(min, max)
                .toShort()
            runCatching { eq.setBandLevel(i.toShort(), level) }
        }
    }

    @Synchronized
    fun setBandLevel(index: Short, levelMb: Short) {
        val eq = equalizer ?: return
        runCatching { eq.setBandLevel(index, levelMb) }
        // Persist the full curve so it survives restarts.
        val current = parseBandGains(SettingsStore.eqBands.value).toMutableMap()
        current[index] = levelMb
        SettingsStore.setEqBands(current.entries.joinToString(",") { "${it.key}:${it.value}" })
    }

    @Synchronized
    fun setBassBoost(strength: Int) {
        SettingsStore.setBassBoost(strength)
        setBassBoostInternal(strength)
    }

    private fun setBassBoostInternal(strength: Int) {
        val bb = bassBoost ?: return
        runCatching {
            if (strength <= 0) {
                bb.enabled = false
            } else {
                bb.setStrength(strength.coerceIn(0, 1000).toShort())
                bb.enabled = true
            }
        }
    }

    @Synchronized
    fun setVirtualizer(strength: Int) {
        SettingsStore.setVirtualizer(strength)
        setVirtualizerInternal(strength)
    }

    private fun setVirtualizerInternal(strength: Int) {
        val v = virtualizer ?: return
        runCatching {
            if (strength <= 0) {
                v.enabled = false
            } else {
                v.setStrength(strength.coerceIn(0, 1000).toShort())
                v.enabled = true
            }
        }
    }

    /** Persisted form is `index:millibels,index:millibels,...`. */
    private fun parseBandGains(raw: String): Map<Short, Short> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(',').mapNotNull { part ->
            val bits = part.split(':')
            if (bits.size != 2) return@mapNotNull null
            val index = bits[0].trim().toShortOrNull() ?: return@mapNotNull null
            val level = bits[1].trim().toShortOrNull() ?: return@mapNotNull null
            index to level
        }.toMap()
    }
}
