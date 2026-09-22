package com.lulu.music.data.source

import com.lulu.music.data.model.ThirdPartyAudioQuality
import com.lulu.music.data.store.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 第三方音源管理：只保存用户导入的音源配置。
 *
 * iOS 用 `@Published var sources { didSet { save() } }` 做到「任何变更立即落盘」；
 * 这里用 [StateFlow] 暴露给 Compose，并在每个变更入口显式落盘。
 */
object UnblockSourceStore {

    /** 当前预设（音源列表）存储键。 */
    private const val PRESETS_KEY = "beans.unblock.presets"

    /** 旧版本的自定义音源键，只在 [PRESETS_KEY] 为空时读取一次。 */
    private const val LEGACY_CUSTOM_KEY = "beans.unblock.custom"

    /** 更早版本的 LX 脚本列表键，已废弃。 */
    private const val LEGACY_LX_KEY = "beans.unblock.lxScripts"

    /** 旧版本的内置预设 id：加载时全部丢弃，只保留用户自己导入的配置。 */
    private val removedBuiltInSourceIDs: Set<String> = setOf(
        "beans.preset.shiqianjiang.lx.v7",
        "beans.preset.shiqianjiang.cr.v7",
        "beans.preset.shiqianjiang.qt.v7",
        "beans.preset.legacy.guoyue.qq.v1",
        "beans.preset.legacy.guoyue.netease.v1",
        "beans.preset.cerumusic.free.v1",
        "beans.preset.quandouyao.free.v1",
        "beans.special.cr.v1",
    )

    private val _sources = MutableStateFlow<List<ThirdPartySource>>(emptyList())

    /** 全部音源，顺序即用户在管理页看到的顺序。 */
    val sources: StateFlow<List<ThirdPartySource>> = _sources.asStateFlow()

    /**
     * 管理页可见列表。
     *
     * iOS 的实现因为存在「隐藏音源」过滤而与 [sources] 不同；此端没有隐藏音源，
     * 因此两者完全等价。
     */
    val managementVisibleSources: List<ThirdPartySource> get() = _sources.value

    /** 已启用音源，供解析器按顺序尝试。 */
    val enabledSources: List<ThirdPartySource> get() = _sources.value.filter { it.enabled }

    private var loaded = false

    /** 幂等；只读取一次持久化数据。 */
    fun load() {
        if (loaded) return
        loaded = true

        val saved = Prefs.readList(PRESETS_KEY, ThirdPartySource.serializer())
            .ifEmpty { Prefs.readList(LEGACY_CUSTOM_KEY, ThirdPartySource.serializer()) }

        // 旧版本的内置预设全部丢弃，并按 id 去重（保留首次出现的那条）。
        val seen = HashSet<String>()
        _sources.value = saved.filter { it.id !in removedBuiltInSourceIDs && seen.add(it.id) }

        // 兼容旧版本时留下的键，读取后一律清除；新装用户这里是空操作。
        Prefs.remove(LEGACY_CUSTOM_KEY)
        Prefs.remove(LEGACY_LX_KEY)
        Prefs.remove("beans.thirdPartyAPIKeys")
        Prefs.remove("beans.paidAudioSource.usageRecorded")
        Prefs.remove("beans.enableUnblock")
        Prefs.remove("beans.useFreeAudioSource")
        Prefs.remove("beans.showThirdPartyKeys")
        save()
    }

    fun addSource(source: ThirdPartySource) = upsert(source)

    /** 批量添加：同 id 覆盖，新 id 追加到末尾。 */
    fun addSources(newSources: List<ThirdPartySource>) {
        if (newSources.isEmpty()) return
        val merged = _sources.value.toMutableList()
        for (source in newSources) {
            val index = merged.indexOfFirst { it.id == source.id }
            if (index >= 0) merged[index] = source else merged.add(source)
        }
        commit(merged)
    }

    /** 同 id 覆盖，否则追加到末尾。 */
    fun upsert(source: ThirdPartySource) {
        val merged = _sources.value.toMutableList()
        val index = merged.indexOfFirst { it.id == source.id }
        if (index >= 0) merged[index] = source else merged.add(source)
        commit(merged)
    }

    /**
     * 按 [by] 上/下移一位。
     * 目标位置会被钳制在 `0..size-1`，越界时落到首/末位；位置没变化则不写盘。
     */
    fun moveSource(id: String, by: Int) {
        val list = _sources.value
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = minOf(maxOf(0, index + by), maxOf(0, list.size - 1))
        if (target == index) return
        val reordered = list.toMutableList()
        val item = reordered.removeAt(index)
        reordered.add(target, item)
        commit(reordered)
    }

    /** 管理页排序；没有隐藏音源过滤，直接等价于 [moveSource]。 */
    fun moveManagementSource(id: String, by: Int) = moveSource(id, by)

    /** 切换启用状态；id 不存在时不做任何事。 */
    fun updateEnabled(id: String, enabled: Boolean) {
        val list = _sources.value
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = list.toMutableList()
        updated[index] = updated[index].copy(enabled = enabled)
        commit(updated)
    }

    /** 删除音源。 @return 是否真的删掉了条目。 */
    fun removeSource(id: String): Boolean {
        val list = _sources.value
        val filtered = list.filterNot { it.id == id }
        if (filtered.size == list.size) return false
        commit(filtered)
        return true
    }

    /**
     * 已启用音源能提供的音质合集：按音源顺序展开后去重（保持首次出现的顺序）。
     * 一个音质都没有时回退到全部音质。
     */
    fun availableThirdPartyQualities(): List<ThirdPartyAudioQuality> {
        val options = _sources.value
            .filter { it.enabled }
            .flatMap { SourceQualities.supported(it) }
            .distinct()
        return if (options.isEmpty()) ThirdPartyAudioQuality.entries.toList() else options
    }

    private fun commit(list: List<ThirdPartySource>) {
        _sources.value = list
        save()
    }

    private fun save() {
        Prefs.writeList(PRESETS_KEY, ThirdPartySource.serializer(), _sources.value)
    }
}
