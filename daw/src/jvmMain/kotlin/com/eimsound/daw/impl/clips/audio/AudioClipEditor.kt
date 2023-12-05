package com.eimsound.daw.impl.clips.audio

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.eimsound.audioprocessor.convertSecondsToPPQ
import com.eimsound.dsp.data.VOLUME_RANGE
import com.eimsound.audioprocessor.oneBarPPQ
import com.eimsound.daw.api.EchoInMirror
import com.eimsound.daw.actions.GlobalEnvelopeEditorEventHandler
import com.eimsound.daw.api.clips.AudioClip
import com.eimsound.daw.api.clips.ClipEditor
import com.eimsound.daw.api.clips.TrackClip
import com.eimsound.daw.components.*
import com.eimsound.daw.dawutils.openMaxValue
import com.eimsound.daw.impl.clips.EditorControls
import com.eimsound.daw.utils.range

class AudioClipEditor(private val clip: TrackClip<AudioClip>) : ClipEditor {
    val noteWidth = mutableStateOf(0.4.dp)
    @Suppress("MemberVisibilityCanBePrivate")
    val horizontalScrollState = ScrollState(0)
    private val envelopeEditor = EnvelopeEditor(
        clip.clip.volumeEnvelope, VOLUME_RANGE, 1F, true,
        horizontalScrollState, GlobalEnvelopeEditorEventHandler
    )

    @Composable
    override fun Editor() {
        Row(Modifier.fillMaxSize()) {
            EditorControls(clip, noteWidth) { }
            Surface(shadowElevation = 2.dp) {
                Box(Modifier.fillMaxSize()) {
                    EditorContent()
                    HorizontalScrollbar(
                        rememberScrollbarAdapter(horizontalScrollState),
                        Modifier.align(Alignment.TopStart).fillMaxWidth()
                    )
                }
            }
        }
    }

    @Composable
    private fun EditorContent() {
        Column(Modifier.fillMaxSize()) {
            val range = remember(clip.start, clip.duration) { clip.start..(clip.start + clip.duration) }
            Timeline(Modifier.zIndex(3F), noteWidth, horizontalScrollState, range, 0.dp,
                EchoInMirror.editUnit, EchoInMirror.currentPosition.oneBarPPQ,
                { EchoInMirror.currentPosition.timeInPPQ = it }
            ) {
                val maxPPQ = EchoInMirror.currentPosition
                    .convertSecondsToPPQ(clip.clip.timeInSeconds).toInt()
                clip.start = it.first.coerceIn(0, maxPPQ)
                clip.duration = it.range.coerceIn(0, maxPPQ)
                clip.track?.clips?.update()
            }
            var contentWidth by remember { mutableStateOf(0) }
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().onGloballyPositioned { contentWidth = it.size.width }) {
                val noteWidthValue = noteWidth.value
                with(density) {
                    val noteWidthPx = noteWidthValue.toPx()
                    val scrollXPPQ = horizontalScrollState.value / noteWidthPx
                    val maxPPQ = EchoInMirror.currentPosition
                        .convertSecondsToPPQ(clip.clip.timeInSeconds).toFloat()
                    val widthPPQ = (contentWidth / noteWidthPx).coerceAtMost(maxPPQ)
                    remember(maxPPQ, noteWidthValue, LocalDensity.current, widthPPQ) {
                        horizontalScrollState.openMaxValue = (((maxPPQ - widthPPQ) *
                                noteWidthValue.toPx()).toInt()).coerceAtLeast(0)
                    }
                    EchoInMirror.currentPosition.apply {
                        EditorGrid(noteWidth, horizontalScrollState, range, ppq, timeSigDenominator, timeSigNumerator)
                    }
                    val color = clip.track?.color ?: MaterialTheme.colorScheme.primary
                    Waveform(
                        clip.clip.thumbnail,
                        EchoInMirror.currentPosition,
                        scrollXPPQ, widthPPQ,
                        clip.clip.timeStretcher?.speedRatio ?: 1F,
                        clip.clip.volumeEnvelope,
                        color, modifier = Modifier.width(noteWidthValue * widthPPQ)
                    )
                    envelopeEditor.Editor(
                        0F, color, noteWidth, true, clipStartTime = clip.start, drawGradient = false
                    )
                    Box {
                        PlayHead(noteWidth, horizontalScrollState,
                            (EchoInMirror.currentPosition.ppqPosition * EchoInMirror.currentPosition.ppq).toFloat(),
                            contentWidth.toDp())
                    }
                }
            }
        }
    }
}
