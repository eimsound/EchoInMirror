package com.eimsound.daw.window.panels.playlist

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.eimsound.audioprocessor.projectDisplayPPQ
import com.eimsound.daw.EchoInMirror
import com.eimsound.daw.actions.doClipsAmountAction
import com.eimsound.daw.api.TrackClip
import com.eimsound.daw.api.processor.Track
import com.eimsound.daw.api.window.Panel
import com.eimsound.daw.api.window.PanelDirection
import com.eimsound.daw.components.EditorGrid
import com.eimsound.daw.components.PlayHead
import com.eimsound.daw.components.TIMELINE_HEIGHT
import com.eimsound.daw.components.Timeline
import com.eimsound.daw.components.utils.EditAction
import com.eimsound.daw.data.getEditUnit
import com.eimsound.daw.utils.BasicEditor
import com.eimsound.daw.utils.fitInUnitCeil
import com.eimsound.daw.utils.mutableStateSetOf
import com.eimsound.daw.utils.openMaxValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val noteWidthRange = 0.02f..5f
val noteWidthSliderRange = (noteWidthRange.start / 0.4F)..(noteWidthRange.endInclusive / 0.4F)
fun Density.calcScroll(event: PointerEvent, noteWidth: MutableState<Dp>, horizontalScrollState: ScrollState,
                       coroutineScope: CoroutineScope, onVerticalScroll: (PointerInputChange) -> Unit) {
    if (event.keyboardModifiers.isShiftPressed) return
    if (event.keyboardModifiers.isCtrlPressed) {
        val change = event.changes[0]
        if (event.keyboardModifiers.isAltPressed) onVerticalScroll(change)
        else {
            val x = change.position.x
            val oldX = (x + horizontalScrollState.value) / noteWidth.value.toPx()
            val newValue = (noteWidth.value.value +
                    (if (change.scrollDelta.y > 0) -0.05F else 0.05F)).coerceIn(noteWidthRange)
            if (newValue != noteWidth.value.value) {
                horizontalScrollState.openMaxValue =
                    (noteWidth.value * EchoInMirror.currentPosition.projectDisplayPPQ).toPx().toInt()
                noteWidth.value = newValue.dp
                coroutineScope.launch {
                    val noteWidthPx = noteWidth.value.toPx()
                    horizontalScrollState.scrollBy(
                        (oldX - (x + horizontalScrollState.value) / noteWidthPx) * noteWidthPx
                    )
                }
            }
        }
        change.consume()
    }
}

class Playlist : Panel, BasicEditor {
    override val name = "Playlist"
    override val direction = PanelDirection.Horizontal
    val selectedClips = mutableStateSetOf<TrackClip<*>>()
    var noteWidth = mutableStateOf(0.2.dp)
    var trackHeight by mutableStateOf(70.dp)
    val verticalScrollState = ScrollState(0)
    val horizontalScrollState = ScrollState(0).apply {
        openMaxValue = (noteWidth.value * EchoInMirror.currentPosition.projectDisplayPPQ).value.toInt()
    }

    internal var action by mutableStateOf(EditAction.NONE)
    internal var contentWidth by mutableStateOf(0.dp)
    internal var selectionX by mutableStateOf(0F)
    internal var selectionY by mutableStateOf(0F)
    internal var selectionStartX = 0F
    internal var selectionStartY = 0F
    internal var deltaX by mutableStateOf(0)
    internal var deltaY by mutableStateOf(0)
    internal var trackHeights = ArrayList<TrackToHeight>()
    internal val deletionList = mutableStateSetOf<TrackClip<*>>()

    companion object {
        var copiedClips: List<TrackClip<*>>? = null
    }

    @Composable
    override fun Icon() {
        Icon(Icons.Filled.QueueMusic, "Playlist")
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        Row(Modifier.onClick { EchoInMirror.windowManager.activePanel = this@Playlist }) {
            Surface(Modifier.width(200.dp).fillMaxHeight().zIndex(5f), shadowElevation = 2.dp, tonalElevation = 2.dp) {
                Column {
                    Surface(shadowElevation = 2.dp, tonalElevation = 4.dp) {
                        Row(Modifier.height(TIMELINE_HEIGHT).fillMaxWidth().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            com.eimsound.daw.components.silder.Slider(
                                noteWidth.value.value / 0.4f,
                                { noteWidth.value = 0.4.dp * it },
                                valueRange = noteWidthSliderRange
                            )
                        }
                    }
                    TrackItems(this@Playlist)
                }
            }
            Box(Modifier.fillMaxSize()) {
                Column {
                    val localDensity = LocalDensity.current
                    Timeline(Modifier.zIndex(3f), noteWidth, horizontalScrollState, EchoInMirror.currentPosition.projectRange) {
                        EchoInMirror.currentPosition.projectRange = it
                    }
                    val coroutineScope = rememberCoroutineScope()
                    Box(Modifier.weight(1f).pointerInput(coroutineScope) {
                        handleMouseEvent(this@Playlist, coroutineScope)
                    }.onGloballyPositioned { with(localDensity) { contentWidth = it.size.width.toDp() } }) {
                        EditorGrid(noteWidth, horizontalScrollState, EchoInMirror.currentPosition.projectRange)
                        val width = (noteWidth.value * EchoInMirror.currentPosition.projectDisplayPPQ)
                            .coerceAtLeast(contentWidth)
                        remember(width, localDensity) {
                            with (localDensity) { horizontalScrollState.openMaxValue = width.toPx().toInt() }
                        }
                        Column(Modifier.verticalScroll(verticalScrollState).fillMaxSize()) {
                            Divider()
                            var i = 0
                            EchoInMirror.bus!!.subTracks.forEach {
                                key(it.id) { i += TrackContent(this@Playlist, it, i, localDensity) }
                            }
                        }
                        TrackSelection(this@Playlist, localDensity, horizontalScrollState, verticalScrollState)
                        PlayHead(noteWidth, horizontalScrollState, contentWidth)
                        VerticalScrollbar(
                            rememberScrollbarAdapter(verticalScrollState),
                            Modifier.align(Alignment.TopEnd).fillMaxHeight()
                        )
                    }
                }
                HorizontalScrollbar(
                    rememberScrollbarAdapter(horizontalScrollState),
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                )
            }
        }
    }

    override fun copy() {
        if (selectedClips.isEmpty()) return
        val startTime = selectedClips.minOf { it.time }
        copiedClips = selectedClips.map { it.copy(it.time - startTime) }
    }

    override fun paste() {
        if (copiedClips?.isEmpty() == true) return
        val startTime = EchoInMirror.currentPosition.timeInPPQ.fitInUnitCeil(getEditUnit())
        val clips = copiedClips!!.map { it.copy(time = it.time + startTime) }
        doClipsAmountAction(clips, false)
        selectedClips.clear()
        selectedClips.addAll(clips)
    }

    override fun delete() {
        if (selectedClips.isEmpty()) return
        doClipsAmountAction(selectedClips, true)
        selectedClips.clear()
    }

    override fun selectAll() { EchoInMirror.bus!!.subTracks.forEach(::selectAllClipsInTrack) }

    private fun selectAllClipsInTrack(track: Track) {
        selectedClips.addAll(track.clips)
        track.subTracks.forEach(::selectAllClipsInTrack)
    }
}

@Deprecated("Will be replaced with a more flexible system")
val mainPlaylist = Playlist()
