package com.lulu.music

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lulu.music.data.prefs.SettingsStore
import kotlinx.coroutines.runBlocking

/**
 * Robolectric 下让「进程级单例 + DataStore」能被**每个测试方法**重新绑定并真正可用。
 *
 * ## 两个必须一起处理的坑（都是实测出来的，不是推测）
 *
 * 1. **DataStore 绑定的是第一个测试方法的 filesDir。**
 *    `SettingsStore` 通过 `Context.dataStore`（`preferencesDataStore("beans_settings")`）拿 DataStore，
 *    这个属性委托把实例缓存在静态字段 `INSTANCE` 上；`SettingsStore.store` 进程内也只赋值一次。
 *    生产环境一个进程只有一个 Application，这没问题；但 Robolectric **每个测试方法**换一个
 *    Application + filesDir。于是从第二个方法起 `store.edit { }` 会写到已被删掉的旧目录，
 *    底层 IOException 被 DataStore 内部吞掉 —— 既不落盘、StateFlow 也不回显
 *    （实测：`setThemeMode(DARK)` 之后 `themeMode` 停在 `SYSTEM`，`beans_settings.preferences_pb` 不存在）。
 *
 * 2. **重绑之后还必须“预热”一次。**
 *    只把委托缓存清掉再 `SettingsStore.init(context)`，DataStore 实例确实换成了当前 filesDir 的，
 *    但紧接着用 `SettingsStore` 的模块级 scope（`CoroutineScope(Dispatchers.IO)`）发出的第一次
 *    `edit` 仍然会静默失败（实测 m2 仍然 `echo=SYSTEM fileExists=false`）。
 *    在 reset 之后**同步**跑一次 `runBlocking { store.edit { … } }`（写一个仅测试用的键）之后就稳定了：
 *    实测 m1/m2 都是 `WARM OK`、`echo=DARK`、`fileExists=true`。
 *    说明 Robolectric 环境里 DataStore 的首次初始化必须在「能真正跑完的调用者」里完成。
 *
 * ## 还有个连带问题：`by lazy` 的 StateFlow 也缓存了旧上游
 *
 * `themeMode` / `audioQuality` 这些 `val x by lazy { watch(...).stateIn(...) }` 一旦求值，就把
 * `store.data` 固定成了上游实例。只换 DataStore 不重置 lazy 的话，写能成功但 StateFlow 不回显。
 * 所以 [resetSettingsDataStore] 默认会把 [COMMON_PREFERENCES] 里的属性打回未初始化。
 *
 * ## 用法
 *
 * 在 `@Before` 里、**访问任何 `SettingsStore` 属性之前**调用（`ApplicationProvider` 会触发
 * `Application.onCreate`，所以它也算「访问之前」，可以直接放在第一行）。
 * 只改动测试进程里的静态缓存，app 的 `main` 源码不需要为测试让步；反射失败时静默跳过，
 * 行为与不调用完全一样，不会因此让测试变红。
 *
 * ## 已知限制（本次改造实测补充）
 *
 * [resetLazy] 的反射重置在 Robolectric 的沙箱里**并不可靠**：`by lazy` 的哨兵是
 * `kotlin.UNINITIALIZED_VALUE.INSTANCE`（不是 `kotlin.LazyKt.UNINITIALIZED`），而沙箱/类加载上下文
 * 不同会拿到不同的哨兵对象；写错哨兵会让 `SynchronizedLazyImpl.getValue()` 走进重新求值分支并
 * 抛 `NullPointerException`。所以**新测试不要依赖「写偏好 → 等 StateFlow 回显」**这条路：
 * 消费方的渲染测试改成把取值**显式作为参数**传进 composable（与 `uiStyle` 等参数的既有约定一致）。
 */
object RobolectricSingletons {

    private const val DELEGATE_FIELD = "dataStore\$delegate"

    /** 测试预热用的键；只在 Robolectric 的临时 filesDir 里出现，不会写进用户数据。 */
    private val WARMUP_KEY = stringPreferencesKey("beans.test.warmup")

    /** 这些是测试里最常读的偏好属性，默认一起把 lazy 缓存打回未初始化。 */
    val COMMON_PREFERENCES = listOf(
        "themeMode",
        "uiStyle",
        "audioQuality",
        "playbackSource",
        "tabLabelsVisible",
    )

    /**
     * 把 [SettingsStore] 重新绑到**当前** Application 上，并把 [resetLazyPreferences] 里的
     * `by lazy` StateFlow 打回未初始化。
     *
     * @param resetLazyPreferences 需要重新绑定的属性名；传空列表时只重绑 DataStore（适用于只写不读的用例）。
     */
    fun resetSettingsDataStore(
        context: Context,
        resetLazyPreferences: List<String> = COMMON_PREFERENCES,
    ) {
        // 1) 清掉属性委托的缓存实例，再让 SettingsStore 重新取一次 —— 新的 DataStore 指向当前 filesDir。
        runCatching {
            val holder = SettingsStore::class.java
            val instance = holder.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            val delegate = holder.getDeclaredField(DELEGATE_FIELD).apply { isAccessible = true }.get(instance)
            delegate.javaClass.getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .set(delegate, null)
        }
        SettingsStore.init(context)

        // 2) 预热：把 DataStore 的首次初始化放在一个能真正跑完的 runBlocking 里。
        runCatching {
            runBlocking {
                storeOf()?.edit { it[WARMUP_KEY] = "1" }
            }
        }

        // 3) 让需要读的 StateFlow 重新绑定到新的 store 上。
        resetLazyPreferences.forEach { resetLazy(it) }
    }

    /**
     * 把 `SettingsStore.<propertyName>` 这个 `by lazy` 打回「未初始化」。
     *
     * JVM 上 `val x by lazy { … }` 就是一个 `SynchronizedLazyImpl` 字段（`x$delegate`），缓存值放在它的
     * 私有 `_value` 里；Kotlin 用 `kotlin.LazyKt.UNINITIALIZED` 这个哨兵表示「还没算过」。
     * 写回哨兵，下一次访问就会重新执行初始化块，从而重新读 `store`。
     *
     * **实测补充（本次改造新增的结论，务必先读）**：在 Kotlin 2.0.21 + Robolectric 下这段反射
     * **实际上不会生效** —— 哨兵现在是 `kotlin.UNINITIALIZED_VALUE.INSTANCE`（`kotlin.LazyKt` 里
     * 已经没有 `UNINITIALIZED` 字段），`_value` 也声明在 `SynchronizedLazyImpl` 自己身上而不是父类；
     * 两处都抛异常，被 `runCatching` 吞掉 → 等价于「什么都不做」。
     *
     * 强行把它「修好」（从别的 delegate 里读哨兵再写回）会在沙箱/类加载上下文不一致时把
     * `_value` 写成**另一份**哨兵，`SynchronizedLazyImpl.getValue()` 于是走重新求值分支、
     * 而 `initializer` 早已置空 —— 直接 `NullPointerException`，并且会让**后续所有测试**一起红
     * （实测：30+ 个既有用例连锁失败）。所以这里保持原样不动：
     * 「写偏好 → 等 StateFlow 回显」这条链路在 Robolectric 下不可靠，
     * 消费方的渲染测试应当**把取值作为参数显式传进去**（见 `AppearanceConsumersRenderTest`）。
     */
    fun resetLazy(propertyName: String) {
        runCatching {
            val holder = SettingsStore::class.java
            val instance = holder.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            val field = holder.getDeclaredField("${propertyName}\$delegate").apply { isAccessible = true }
            val lazy = field.get(instance) ?: return@runCatching
            val sentinel = Class.forName("kotlin.LazyKt")
                .getDeclaredField("UNINITIALIZED")
                .apply { isAccessible = true }
                .get(null)
            val valueField = lazy.javaClass.superclass
                .getDeclaredField("_value")
                .apply { isAccessible = true }
            valueField.set(lazy, sentinel)
        }
    }

    /** 当前 [SettingsStore] 实际在用的 DataStore（反射读 `private lateinit var store`）。 */
    @Suppress("UNCHECKED_CAST")
    private fun storeOf(): DataStore<Preferences>? = runCatching {
        val holder = SettingsStore::class.java
        val instance = holder.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        holder.getDeclaredField("store").apply { isAccessible = true }.get(instance) as DataStore<Preferences>
    }.getOrNull()
}
