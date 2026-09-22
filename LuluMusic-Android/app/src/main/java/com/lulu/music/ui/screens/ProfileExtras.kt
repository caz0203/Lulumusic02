package com.lulu.music.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.LuluLinks
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「我的」页面的**交流群**与**自愿赞助**模块。
 *
 * 展示内容全部来自 [LuluLinks]。
 * 交流群没有配置二维码图片时，会用 ZXing 从群链接本地生成一个二维码，
 * 因此只填链接也能扫码入群。
 */

// ---------------------------------------------------------------------------------------------
// MARK: - 交流群
// ---------------------------------------------------------------------------------------------

/** 交流群入口卡片（点击打开二维码面板）。 */
@Composable
fun LuluCommunityCard(style: BeansUIStyle) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var showSheet by remember { mutableStateOf(false) }

    BeansGlass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        style = style,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                ) {
                    BeansHaptics.tap()
                    showSheet = true
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Group,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = beansLocalized("交流群", "Community"),
                    color = colors.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (LuluLinks.COMMUNITY_NAME.isNotBlank()) {
                        beansLocalized(
                            "「${LuluLinks.COMMUNITY_NAME}」扫码或点击加入",
                            "Join \"${LuluLinks.COMMUNITY_NAME}\" by QR or link",
                        )
                    } else {
                        beansLocalized("扫码加入交流群", "Scan to join the community")
                    },
                    color = colors.comment,
                    fontSize = 11.sp,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.comment,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (showSheet) {
        BeansBottomSheet(onDismissRequest = { showSheet = false }) {
            CommunitySheetContent()
        }
    }
}

@Composable
private fun CommunitySheetContent() {
    val colors = BeansTheme.colors
    val context = LocalContext.current
    val groupName = LuluLinks.COMMUNITY_NAME.ifBlank {
        beansLocalized("交流群", "Community")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = beansLocalized("交流群", "Community"),
            color = colors.comment,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = groupName,
            color = colors.label,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        QrBlock(
            drawableName = LuluLinks.COMMUNITY_QR,
            fallbackContent = LuluLinks.COMMUNITY_URL,
            emptyTitle = beansLocalized("二维码待添加", "QR code pending"),
            emptyHint = beansLocalized(
                "在 LuluLinks.kt 里填写 COMMUNITY_URL 或 COMMUNITY_QR",
                "Set COMMUNITY_URL or COMMUNITY_QR in LuluLinks.kt",
            ),
        )

        Spacer(Modifier.height(14.dp))
        Text(
            text = LuluLinks.COMMUNITY_NOTE.ifBlank {
                beansLocalized("扫码加入交流群", "Scan to join the community")
            },
            color = colors.label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )

        if (LuluLinks.COMMUNITY_URL.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            BeansGlassButton(
                title = beansLocalized("点击加入群聊", "Join the group"),
                onClick = {
                    BeansHaptics.tap()
                    openUrl(context, LuluLinks.COMMUNITY_URL)
                },
                icon = Icons.Rounded.OpenInNew,
                prominent = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = LuluLinks.COMMUNITY_URL,
                color = colors.comment,
                fontSize = 10.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 自愿赞助
// ---------------------------------------------------------------------------------------------

/** 自愿赞助卡片（可展开：收款码 + 赞助人员榜单）。 */
@Composable
fun LuluDonationCard(style: BeansUIStyle) {
    val colors = BeansTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val donors = LuluLinks.donorsRanked

    BeansGlass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        style = style,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) {
                        BeansHaptics.tap()
                        expanded = !expanded
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = beansLocalized("自愿赞助", "Voluntary sponsorship"),
                        color = colors.label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (expanded) {
                            beansLocalized("点击收起赞助信息", "Tap to collapse")
                        } else {
                            beansLocalized("点击展开赞助信息", "Tap to expand")
                        },
                        color = colors.comment,
                        fontSize = 11.sp,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.comment,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(16.dp))

                    QrBlock(
                        drawableName = LuluLinks.DONATION_QR,
                        fallbackContent = null,
                        emptyTitle = beansLocalized("收款码待添加", "Payment QR pending"),
                        emptyHint = beansLocalized(
                            "在 LuluLinks.kt 里填写 DONATION_QR 后显示",
                            "Set DONATION_QR in LuluLinks.kt to show it here",
                        ),
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = LuluLinks.DONATION_NOTE.ifBlank {
                            beansLocalized(
                                "请使用微信扫描上方二维码完成赞助。",
                                "Scan the QR code above with WeChat to sponsor.",
                            )
                        },
                        color = colors.comment,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 只有配置了 wxp:// 链接时才提供跳转按钮；否则扫码即可。
                    if (LuluLinks.WECHAT_PAY_URL.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        BeansGlassButton(
                            title = beansLocalized("打开微信", "Open WeChat"),
                            onClick = {
                                BeansHaptics.tap()
                                openWeChat(context)
                            },
                            icon = Icons.Rounded.OpenInNew,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = beansLocalized("赞助人员", "Sponsors"),
                            color = colors.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = beansLocalized("按金额排序", "By amount"),
                            color = colors.comment,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    if (donors.isEmpty()) {
                        Text(
                            text = beansLocalized(
                                "暂无赞助记录（在 LuluLinks.kt 的 DONORS 中添加）",
                                "No sponsors yet (add them to DONORS in LuluLinks.kt)",
                            ),
                            color = colors.comment,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        donors.forEachIndexed { index, donor ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            (if (index == 0) colors.accent else colors.comment)
                                                .copy(alpha = if (index == 0) 0.16f else 0.08f),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        color = if (index == 0) colors.accent else colors.comment,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = donor.name,
                                    color = colors.label,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = String.format("¥ %.2f", donor.amount),
                                    color = if (index == 0) colors.accent else colors.label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 二维码展示
// ---------------------------------------------------------------------------------------------

/**
 * 二维码区域。
 *
 * 优先用 [drawableName] 指定的图片；没有图片但提供了 [fallbackContent] 时，
 * 用 ZXing 本地生成一个二维码；两者都没有则显示占位提示。
 */
@Composable
private fun QrBlock(
    drawableName: String,
    fallbackContent: String?,
    emptyTitle: String,
    emptyHint: String,
) {
    val colors = BeansTheme.colors
    val context = LocalContext.current

    val resId = remember(drawableName) {
        if (drawableName.isBlank()) 0
        else runCatching {
            context.resources.getIdentifier(drawableName, "drawable", context.packageName)
        }.getOrDefault(0)
    }

    // 后台生成，避免二维码编码卡住主线程。
    val generated by produceState<ImageBitmap?>(initialValue = null, fallbackContent) {
        value = if (resId != 0 || fallbackContent.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) { generateQrImage(fallbackContent) }
        }
    }

    when {
        resId != 0 -> QrImage(painterResource(resId))
        generated != null -> QrImage(rememberImagePainter(generated!!))
        else -> QrPlaceholder(title = emptyTitle, hint = emptyHint, tint = colors.comment)
    }
}

/** 按图片自身比例展示，竖版收款码不会被压成小方块。 */
@Composable
private fun QrImage(painter: Painter) {
    val intrinsic = painter.intrinsicSize
    val ratio = if (intrinsic.height > 0f && intrinsic.width > 0f) {
        intrinsic.width / intrinsic.height
    } else {
        1f
    }
    // 竖版（比例 < 1）时收窄宽度，避免二维码过高需要滚动。
    val widthFraction = if (ratio < 0.95f) 0.66f else 0.8f

    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White),
    )
}

@Composable
private fun QrPlaceholder(title: String, hint: String, tint: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BeansTheme.colors.glassFill),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.QrCode2,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                color = BeansTheme.colors.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                color = tint,
                fontSize = 11.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - helpers
// ---------------------------------------------------------------------------------------------

/** 把生成的 [ImageBitmap] 包成 [Painter]（`remember` 保证不重复创建）。 */
@Composable
private fun rememberImagePainter(bitmap: ImageBitmap): Painter =
    remember(bitmap) { androidx.compose.ui.graphics.painter.BitmapPainter(bitmap) }

/** 用 ZXing 生成二维码位图；失败返回 null（界面会退化为占位提示）。 */
private fun generateQrImage(content: String, size: Int = 640): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
    )
    val matrix = MultiFormatWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        hints,
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix.get(x, y)) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    bitmap.asImageBitmap()
}.getOrNull()

private fun openUrl(context: Context, url: String) {
    val ok = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
    if (!ok) {
        BeansToastCenter.show(beansLocalized("无法打开链接", "Could not open the link"))
    }
}

/** 尝试唤起微信；未配置收款链接时只做提示。 */
private fun openWeChat(context: Context) {
    val url = LuluLinks.WECHAT_PAY_URL
    if (url.isBlank()) {
        BeansToastCenter.show(
            beansLocalized("请扫描上方二维码完成赞助", "Please scan the QR code above"),
        )
        return
    }
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    if (!opened) {
        val fallback = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("weixin://")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (!fallback) {
            BeansToastCenter.show(beansLocalized("无法打开微信", "Could not open WeChat"))
        }
    }
}
