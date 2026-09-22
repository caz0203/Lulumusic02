package com.lulu.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.components.BeansCoverImage
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansProgressLine
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.theme.BeansTheme

/**
 * Dockable mini player shown above the tab bar whenever something is loaded.
 * Tapping the row opens the full player; the transport buttons work inline.
 */
@Composable
fun MiniPlayerBar(onExpand: () -> Unit) {
    val song by PlaybackController.currentSong.collectAsState()
    val isPlaying by PlaybackController.isPlaying.collectAsState()
    val position by PlaybackController.positionMs.collectAsState()
    val duration by PlaybackController.durationMs.collectAsState()
    val isBuffering by PlaybackController.isBuffering.collectAsState()
    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val colors = BeansTheme.colors

    val current = song
    AnimatedVisibility(
        visible = current != null,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        val interaction = remember { MutableInteractionSource() }
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp)) {
            BeansGlass(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                style = uiStyle,
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BeansCoverImage(
                            url = current?.coverURL,
                            size = 44.dp,
                            cornerRadius = 10.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    Modifier.beansRowTap(interaction, onExpand),
                                ),
                        ) {
                            Text(
                                text = current?.name.orEmpty(),
                                color = colors.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = current?.artists.orEmpty(),
                                color = colors.comment,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BeansTransportIcon(
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            onToggle = {
                                BeansHaptics.tap()
                                PlaybackController.togglePlayPause()
                            },
                        )
                        BeansNextIcon(onNext = {
                            BeansHaptics.tap()
                            PlaybackController.next()
                        })
                    }
                    // Progress hairline pinned to the bottom of the card.
                    BeansProgressLine(
                        progress = position.toDouble() / 1000.0,
                        duration = duration.toDouble() / 1000.0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        height = 2.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun BeansTransportIcon(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onToggle: () -> Unit,
) {
    val colors = BeansTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .beansRowTap(source, onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            androidx.compose.material3.CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun BeansNextIcon(onNext: () -> Unit) {
    val colors = BeansTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .beansRowTap(source, onNext),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.SkipNext,
            contentDescription = null,
            tint = colors.label,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun Modifier.beansRowTap(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interaction,
    indication = null,
    onClick = onClick,
)
