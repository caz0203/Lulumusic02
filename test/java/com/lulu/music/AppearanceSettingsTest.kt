package com.lulu.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lulu.music.data.prefs.BeansFloatingEffect
import com.lulu.music.data.prefs.BeansTabIconStyle
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.ui.BeansDockOffsetRange
import com.lulu.music.ui.BeansDockRadiusRange
import com.lulu.music.ui.BeansDockWidthRange
import com.lulu.music.ui.RootTab
import com.lulu.music.ui.beansDockGeometry
import com.lulu.music.ui.beansHomeBackgroundHex
import com.lulu.music.ui.beansTabIcon
import com.lulu.music.ui.beansToggleRootTab
import com.lulu.music.ui.components.beansFloatingParticles
import com.lulu.music.ui.components.beansGlassFill
import com.lulu.music.ui.components.beansGlassSpec
import com.lulu.music.ui.screens.beansSettingsCardRows
import com.lulu.music.ui.screens.beansSettingsSearchIndex
import com.lulu.music.ui.screens.dailyPicksPresentation
import com.lulu.music.ui.screens.settingsSearchMatch
import com.lulu.music.ui.theme.beansColors
import com.lulu.music.ui.visibleRootTabIndices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「主题自定义无效」的回归防线：**纯逻辑**测试，不依赖渲染、不依赖网络。
 *
 * 为什么必须有这一组：`SettingsScreen` 用 `BeansUIStyle.entries` 渲染分段控件，所以
 * 「多加一个枚举值」是零成本的 —— 如果渲染层没有真实分支，用户点完发现毫无变化，
 * 就又是一个「无效」bug。这里直接钉住四种样式在材质 / 几何层面的差异，以及
 * 新增设置项的取值规则（它们各自都有真实消费方，消费方的渲染测试见
 * [AppearanceConsumersRenderTest]）。
 */
class AppearanceSettingsTest {

    private val lightColors = beansColors(isDark = false)
    private val darkColors = beansColors(isDark = true)

    // ------------------------------------------------------------------
    // 1. BeansUIStyle 四值：材质与几何必须真的不同
    // ------------------------------------------------------------------

    @Test
    fun fourUiStylesHavePairwiseDistinctSpecs() {
        val specs = BeansUIStyle.entries.associateWith { beansGlassSpec(it) }

        assertEquals("参考图是四个样式", 4, BeansUIStyle.entries.size)
        BeansUIStyle.entries.forEach { a ->
            BeansUIStyle.entries.filter { it != a }.forEach { b ->
                assertNotEquals(
                    "样式 $a 与 $b 的渲染参数完全相同 —— 那就是「点了没反应」",
                    specs[a],
                    specs[b],
                )
            }
        }
    }

    @Test
    fun frostedIsHeavierBlurAndLowerOpacityThanLiquid() {
        val liquid = beansGlassSpec(BeansUIStyle.LIQUID)
        val frosted = beansGlassSpec(BeansUIStyle.FROSTED)

        assertTrue(
            "磨砂玻璃必须压低底色不透明度：${frosted.fillAlphaScale} !< ${liquid.fillAlphaScale}",
            frosted.fillAlphaScale < liquid.fillAlphaScale,
        )
        assertTrue(
            "磨砂玻璃必须有额外的重模糊：${frosted.frostBlurRadius} !> ${liquid.frostBlurRadius}",
            frosted.frostBlurRadius > liquid.frostBlurRadius,
        )
        assertTrue("磨砂玻璃的环境色斑要更重", frosted.ambientGlowScale > liquid.ambientGlowScale)
        assertTrue("磨砂玻璃要有薄雾叠加", frosted.frostVeilAlpha > 0f)
        assertEquals("磨砂玻璃只换材质，不改几何", liquid.cardPadding, frosted.cardPadding)
        assertEquals("磨砂玻璃只换材质，不改行高", liquid.rowVerticalPadding, frosted.rowVerticalPadding)
    }

    @Test
    fun compactTightensCornersPaddingAndRowHeight() {
        val liquid = beansGlassSpec(BeansUIStyle.LIQUID)
        val compact = beansGlassSpec(BeansUIStyle.COMPACT)

        val compactCap = requireNotNull(compact.cornerCap) { "紧凑淡雅必须有更小的圆角上限" }
        assertTrue("紧凑淡雅的圆角要更小", compactCap < 24.dp)
        assertTrue("紧凑淡雅的卡片内边距要更紧", compact.cardPadding < liquid.cardPadding)
        assertTrue("紧凑淡雅的行高要更紧", compact.rowVerticalPadding < liquid.rowVerticalPadding)
        assertTrue("紧凑淡雅的阴影要更薄", compact.shadowRadius < liquid.shadowRadius)
        assertTrue("紧凑淡雅的环境色斑要更弱", compact.ambientGlowScale < liquid.ambientGlowScale)
    }

    @Test
    fun liquidAndNativeCleanKeepTodaysBehaviour() {
        val liquid = beansGlassSpec(BeansUIStyle.LIQUID)
        val native = beansGlassSpec(BeansUIStyle.NATIVE_CLEAN)

        // 液态：原样（不封顶圆角、16dp 内边距、1 倍填充 / 高光 / 描边、9dp 阴影）。
        assertEquals(null, liquid.cornerCap)
        assertEquals(16.dp, liquid.cardPadding)
        assertEquals(1f, liquid.fillAlphaScale, 0f)
        assertEquals(1f, liquid.sheenAlphaScale, 0f)
        assertEquals(1f, liquid.hairlineAlphaScale, 0f)
        assertEquals(0.dp, liquid.frostBlurRadius)
        assertEquals(0f, liquid.frostVeilAlpha, 0f)

        // Apple 简洁：改造前的 18dp 圆角上限 / 13dp 内边距 / 低存在感平面底色 / 不画色斑。
        assertEquals(18.dp, native.cornerCap)
        assertEquals(13.dp, native.cardPadding)
        assertTrue("简洁样式的高光要压到 1/3 左右", native.sheenAlphaScale < 0.4f)
        assertTrue("简洁样式的描边要压到一半左右", native.hairlineAlphaScale < 0.6f)
        assertEquals(0f, native.ambientGlowScale, 0f)
        assertEquals(9.dp, native.shadowRadius)
    }

    @Test
    fun glassFillIsDistinctPerStyleAndFrostedIsThinner() {
        val fills = BeansUIStyle.entries.associateWith { beansGlassFill(darkColors, it) }

        assertEquals("磨砂玻璃 / 紧凑淡雅 / 液态系必须给出不同底色", 3, fills.values.toSet().size)
        assertEquals(
            "液态与 Apple 简洁的**填充**本来就一样（差异在高光 / 描边 / 几何，见 spec 测试）",
            fills[BeansUIStyle.LIQUID],
            fills[BeansUIStyle.NATIVE_CLEAN],
        )
        assertTrue(
            "磨砂玻璃底色比液态更透：${fills[BeansUIStyle.FROSTED]} !< ${fills[BeansUIStyle.LIQUID]}",
            requireNotNull(fills[BeansUIStyle.FROSTED]).alpha <
                requireNotNull(fills[BeansUIStyle.LIQUID]).alpha,
        )
        assertEquals(
            "Apple 简洁保持改造前的底色（不被压低）",
            darkColors.glassFill,
            fills[BeansUIStyle.NATIVE_CLEAN],
        )
        // 浅色模式下也成立，避免「只在深色生效」的假修复。
        assertTrue(beansGlassFill(lightColors, BeansUIStyle.FROSTED).alpha < lightColors.glassFill.alpha)
    }

    @Test
    fun liquidTintOnlyAffectsLiquidFamily() {
        val tint = Color(0xFFFF0000)

        val tintedLiquid = beansGlassFill(darkColors, BeansUIStyle.LIQUID, tint)
        val tintedFrosted = beansGlassFill(darkColors, BeansUIStyle.FROSTED, tint)
        val tintedNative = beansGlassFill(darkColors, BeansUIStyle.NATIVE_CLEAN, tint)
        val tintedCompact = beansGlassFill(darkColors, BeansUIStyle.COMPACT, tint)

        assertNotEquals("液态容器颜色必须真的改变液态底色", tintedLiquid, darkColors.glassFill)
        assertEquals("换色不能改不透明度", darkColors.glassFill.alpha, tintedLiquid.alpha, 0.0001f)
        assertEquals("液态底色应变成所选颜色", tint.red, tintedLiquid.red, 0.0001f)
        assertEquals("磨砂玻璃同属液态系", tint.red, tintedFrosted.red, 0.0001f)
        assertEquals("系统原生玻璃不受影响（参考图原话）", darkColors.glassFill, tintedNative)
        assertEquals(
            "紧凑淡雅不跟随液态容器颜色（色相不变）",
            darkColors.glassFill.red,
            tintedCompact.red,
            0.0001f,
        )
        assertEquals(
            "紧凑淡雅只是按自己的样式压低透明度",
            darkColors.glassFill.alpha * beansGlassSpec(BeansUIStyle.COMPACT).fillAlphaScale,
            tintedCompact.alpha,
            0.0001f,
        )
        assertEquals(
            "无色值时必须完全等于改造前的底色",
            darkColors.glassFill,
            beansGlassFill(darkColors, BeansUIStyle.LIQUID, null),
        )
    }

    // ------------------------------------------------------------------
    // 2. 设置搜索：中英都匹配 + 命中自动展开
    // ------------------------------------------------------------------

    @Test
    fun emptyQueryShowsEverything() {
        val match = settingsSearchMatch("   ")

        assertFalse(match.active)
        beansSettingsCardRows.forEach { row ->
            assertTrue("空查询必须显示 ${row.titleZh}", match.showsSection(row.key))
        }
        assertTrue(match.showsChildTitle("theme", "隐藏主页用户名"))
    }

    @Test
    fun chineseChildQueryKeepsItsCardAndExpandsIt() {
        val match = settingsSearchMatch("隐藏主页用户名")

        assertTrue(match.active)
        assertTrue("命中子项的小节必须留下并自动展开（theme）", match.showsSection("theme"))
        assertTrue(match.showsChildTitle("theme", "隐藏主页用户名"))
        assertFalse("没命中的子项要被过滤掉", match.showsChildTitle("theme", "隐藏自愿赞助"))
        assertFalse("没命中的卡片整行消失（播放设置）", match.showsSection("playback"))
    }

    @Test
    fun englishQueryMatchesChineseUiTitles() {
        // 界面是中文时搜英文，同样要命中（索引里中英都登记）。
        val byEnglish = settingsSearchMatch("Hide home nickname")

        assertTrue(byEnglish.showsSection("theme"))
        assertTrue(byEnglish.showsChildTitle("theme", "隐藏主页用户名"))
        assertTrue(
            "英文命中时中文标题的兄弟项不应跟着显示",
            !byEnglish.showsChildTitle("theme", "隐藏自愿赞助"),
        )
        // 反向：界面是英文（渲染成 "Hide home nickname"）时搜中文。
        val byChinese = settingsSearchMatch("隐藏主页用户名")
        assertTrue(byChinese.showsChildTitle("theme", "Hide home nickname"))
    }

    @Test
    fun cardTitleQueryShowsEveryChildOfThatSalon() {
        val match = settingsSearchMatch("主题模式")

        assertTrue("命中卡片标题时整段展开", match.showsSection("theme"))
        assertTrue("卡片标题命中 → 子项全部保留", match.showsChildTitle("theme", "隐藏自愿赞助"))
        assertTrue(match.showsChildTitle("theme", "液态容器颜色"))
    }

    @Test
    fun indexIsHonestEveryDeclaredLabelResolves() {
        // 索引里登记过的每一项都必须：1) 让所在小节留下；2) 自身命中。
        // 否则搜索会把人带到一个空的展开区（比不命中更糟）。
        beansSettingsSearchIndex.forEach { (sectionKey, labels) ->
            labels.forEach { (zh, en) ->
                val byZh = settingsSearchMatch(zh)
                assertTrue("搜索「$zh」时小节 $sectionKey 必须可见", byZh.showsSection(sectionKey))
                assertTrue("搜索「$zh」时它自己必须可见", byZh.showsChildTitle(sectionKey, zh, en))

                val byEn = settingsSearchMatch(en)
                assertTrue("搜索「$en」时小节 $sectionKey 必须可见", byEn.showsSection(sectionKey))
                assertTrue("搜索「$en」时它自己必须可见", byEn.showsChildTitle(sectionKey, zh, en))
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. 底栏：图标样式 / 入口显示 / 悬浮底栏几何
    // ------------------------------------------------------------------

    @Test
    fun threeTabIconStylesGiveThreeDifferentIconSets() {
        RootTab.entries.forEach { tab ->
            val icons = BeansTabIconStyle.entries.map { beansTabIcon(tab, it) }
            assertEquals(
                "$tab 的三种底栏图标样式必须给出三种不同图标",
                3,
                icons.toSet().size,
            )
        }
        // 整套图标也必须三套互不相同（换样式真的换掉整排图标）。
        val sets = BeansTabIconStyle.entries.map { style -> RootTab.entries.map { beansTabIcon(it, style) } }
        assertEquals("三套底栏图标必须互不相同", 3, sets.toSet().size)
        assertEquals(
            "Apple Music 样式 = 改造前那套图标",
            RootTab.HOME.icon,
            beansTabIcon(RootTab.HOME, BeansTabIconStyle.APPLE_MUSIC),
        )
    }

    @Test
    fun visibleRootTabsDefaultsToAllAndNeverDropsHome() {
        assertEquals(RootTab.entries.indices.toList(), visibleRootTabIndices(""))

        val onlyFeatured = visibleRootTabIndices("FEATURED")
        assertTrue("即使只勾了精选，主页也必须保留", 0 in onlyFeatured)
        assertTrue(1 in onlyFeatured)
        assertEquals(2, onlyFeatured.size)

        val hiddenHome = beansToggleRootTab("", 0)
        assertEquals("主页入口不可关闭", "", hiddenHome)

        val toggledOff = beansToggleRootTab("", 3)
        assertFalse("切换一次应当关掉 PROFILE", 3 in visibleRootTabIndices(toggledOff))
        assertTrue("主页始终在", 0 in visibleRootTabIndices(toggledOff))
        val toggledBack = beansToggleRootTab(toggledOff, 3)
        assertTrue(3 in visibleRootTabIndices(toggledBack))
    }

    @Test
    fun dockGeometryFollowsSlidersAndClampsOutOfRangeValues() {
        val dock = beansDockGeometry(radius = 20f, width = 300f, offsetX = -40f, offsetY = 12f)
        assertEquals(20f, dock.cornerRadius.value, 0.001f)
        assertEquals(300f, dock.widthCap.value, 0.001f)
        assertEquals(-40f, dock.offsetX.value, 0.001f)
        assertEquals(12f, dock.offsetY.value, 0.001f)

        val clamped = beansDockGeometry(radius = 9_999f, width = -5f, offsetX = 9_999f, offsetY = -9_999f)
        assertEquals(BeansDockRadiusRange.endInclusive, clamped.cornerRadius.value, 0.001f)
        assertEquals(BeansDockWidthRange.start, clamped.widthCap.value, 0.001f)
        assertEquals(BeansDockOffsetRange.endInclusive, clamped.offsetX.value, 0.001f)
        assertEquals(BeansDockOffsetRange.start, clamped.offsetY.value, 0.001f)
    }

    // ------------------------------------------------------------------
    // 4. 主页背景色 / 漂浮特效 / 每日推荐样式
    // ------------------------------------------------------------------

    @Test
    fun homeBackgroundPicksTheBranchForTheCurrentMode() {
        assertEquals("#111111", beansHomeBackgroundHex(true, "#FFFFFF", "#111111", "#999999"))
        assertEquals("#FFFFFF", beansHomeBackgroundHex(false, "#FFFFFF", "#111111", "#999999"))
        assertEquals(
            "两支都为空时回落到旧的通用背景色（老用户设置不丢）",
            "#999999",
            beansHomeBackgroundHex(true, "", "", "#999999"),
        )
        assertEquals("全部为空时返回空串（用默认氛围渐变）", "", beansHomeBackgroundHex(false, "", "", ""))
        assertEquals(
            "只设了一支时，另一支也要回落（不能整块不生效）",
            "#999999",
            beansHomeBackgroundHex(false, "", "#111111", "#999999"),
        )
    }

    @Test
    fun floatingEffectProducesParticlesOnlyWhenEnabled() {
        assertTrue("关闭时必须一个粒子都不画", beansFloatingParticles(BeansFloatingEffect.OFF).isEmpty())

        val snow = beansFloatingParticles(BeansFloatingEffect.SNOW)
        val text = beansFloatingParticles(BeansFloatingEffect.TEXT)

        assertTrue(snow.isNotEmpty())
        assertTrue(text.isNotEmpty())
        assertTrue("雪花只用雪花字形", snow.all { it.glyph in listOf("❄", "❅", "❆") })
        assertFalse("文字特效不能画雪花", text.all { it.glyph in listOf("❄", "❅", "❆") })
        assertNotEquals("两种特效的粒子数量不同（观感不同）", snow.size, text.size)
        (snow + text).forEach { particle ->
            assertTrue("x 比例必须在 0..1：${particle.xFraction}", particle.xFraction in 0f..1f)
            assertTrue("y 比例必须在 0..1：${particle.yFraction}", particle.yFraction in 0f..1f)
            assertTrue("缩放在合理范围：${particle.scale}", particle.scale in 0.5f..1.5f)
        }
    }

    @Test
    fun dailyPicksLegacySwitchChangesTheRenderingPlan() {
        val modern = dailyPicksPresentation(legacy = false, nativeClean = false)
        val legacy = dailyPicksPresentation(legacy = true, nativeClean = false)
        val modernClean = dailyPicksPresentation(legacy = false, nativeClean = true)

        assertFalse("默认是新版横滑卡片", modern.legacyList)
        assertTrue("打开开关必须换成旧版竖排列表", legacy.legacyList)
        assertNotEquals("新版卡片宽度随样式变化（几何没有被写死）", modern.cardSide, modernClean.cardSide)
    }
}
