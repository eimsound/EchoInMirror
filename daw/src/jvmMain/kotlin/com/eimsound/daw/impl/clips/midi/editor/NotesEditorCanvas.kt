package com.eimsound.daw.impl.clips.midi.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.fastForEach
import com.eimsound.audioprocessor.data.defaultScale
import com.eimsound.audioprocessor.data.midi.NoteMessage
import com.eimsound.audioprocessor.data.midi.colorSaturation
import com.eimsound.audioprocessor.data.midi.getNoteName
import com.eimsound.audioprocessor.projectDisplayPPQ
import com.eimsound.daw.api.EchoInMirror
import com.eimsound.daw.api.EditorTool
import com.eimsound.daw.api.asMidiTrackClipOrNull
import com.eimsound.daw.api.processor.Track
import com.eimsound.daw.api.window.EditorExtension
import com.eimsound.daw.api.window.EditorExtensions
import com.eimsound.daw.components.EditorGrid
import com.eimsound.daw.components.KEYBOARD_KEYS
import com.eimsound.daw.components.LocalFloatingLayerProvider
import com.eimsound.daw.components.dragdrop.dropTarget
import com.eimsound.daw.components.utils.*
import com.eimsound.daw.dawutils.SHOULD_SCROLL_REVERSE
import com.eimsound.daw.dawutils.openMaxValue
import com.eimsound.daw.utils.mapValue
import kotlin.math.absoluteValue

internal var selectionStartX = 0F
internal var selectionStartY = 0
internal var selectionX by mutableStateOf(0F)
internal var selectionY by mutableStateOf(0)
internal var deltaX by mutableStateOf(0)
internal var deltaY by mutableStateOf(0)
internal var action by mutableStateOf(EditAction.NONE)
internal var resizeDirectionRight = false
internal var cursor by mutableStateOf(PointerIcon.Default)
internal var currentNote = 0

private const val MIN_NOTE_WIDTH_WITH_KEY_NAME = 30

private data class NoteDrawObject(val note: NoteMessage, val offset: Offset, val size: Size, val color: Color,
                                  val keyNameOffset: Offset? = null, val textLayoutResult: TextLayoutResult? = null)
private data class BackingTrack(val track: Track, val notes: ArrayList<NoteDrawObject>)


val notesEditorExtensions: MutableList<EditorExtension> = mutableStateListOf()

@Composable
private fun DefaultMidiClipEditor.EditorHorizontalGrid() {
    val highlightNoteColor = MaterialTheme.colorScheme.onBackground.copy(0.05F)
    val outlineColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxSize().graphicsLayer {  }) {
        val noteHeightPx = noteHeight.toPx()
        val isDarkTheme = EchoInMirror.windowManager.isDarkTheme
        val canvasHeight = size.height
        val verticalScrollValue = verticalScrollState.value
        for (i in (verticalScrollValue / noteHeightPx).toInt()..
                ((verticalScrollValue + canvasHeight) / noteHeightPx).toInt()) {
            val y = i * noteHeightPx - verticalScrollValue
            if (y < 0) continue
            drawLine(outlineColor, Offset(0f, y), Offset(size.width, y), density)
            if (defaultScale.scale[11 - (i + 4) % 12] != isDarkTheme)
                drawRect(highlightNoteColor, Offset(0f, y), Size(size.width, noteHeightPx))
        }
    }
}

@Composable
internal fun NotesEditorCanvas(editor: DefaultMidiClipEditor) {
    val coroutineScope = rememberCoroutineScope()
    val floatingLayerProvider = LocalFloatingLayerProvider.current
    editor.apply {
        Box(
            Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.background)
                .scrollable(verticalScrollState, Orientation.Vertical, reverseDirection = SHOULD_SCROLL_REVERSE)
                .onGloballyPositioned { offsetOfRoot = it.positionInRoot() }
                .pointerInput(coroutineScope, editor) {
                    handleMouseEvent(coroutineScope, editor, floatingLayerProvider)
                }
                .pointerInput(editor) { // Double click
                    awaitPointerEventScope {
                        var time = 0L
                        while (true) {
                            val event = awaitFirstDown(false)
                            if (action != EditAction.NONE || EchoInMirror.editorTool != EditorTool.CURSOR) continue
                            time = if (event.previousUptimeMillis - time < viewConfiguration.longPressTimeoutMillis && getClickedNotes(event.position) == null) {
                                createNewNote()
                                0
                            } else event.previousUptimeMillis
                        }
                    }
                }
                .dropTarget({ _, _ -> true }) { _, pos ->
                    println(pos)
                    true
                }
        ) {
            val primaryColor = MaterialTheme.colorScheme.primary
            val labelMediumStyle = MaterialTheme.typography.labelMedium
            val displayPPQ = EchoInMirror.currentPosition.projectDisplayPPQ
            val localDensity = LocalDensity.current
            remember(displayPPQ, localDensity) {
                with (localDensity) { horizontalScrollState.openMaxValue = (noteWidth.value.toPx() * displayPPQ).toInt() }
            }

            val measurer = rememberTextMeasurer(127)

            val trackClip = editor.clip
            val range = remember(trackClip.time, trackClip.duration) { trackClip.time..(trackClip.time + trackClip.duration) }
            EchoInMirror.currentPosition.apply {
                EditorGrid(noteWidth, horizontalScrollState, range, ppq, timeSigDenominator, timeSigNumerator)
            }
            EditorHorizontalGrid()
            notesEditorExtensions.EditorExtensions(true)

            val maxKeyNameSize = Constraints(0, (20 * localDensity.density).toInt(),
                0, (16 * localDensity.density).toInt())
            val keyNameLayerResults = remember(localDensity) { arrayOfNulls<TextLayoutResult>(127) }
            val disabledKeyNameLayerResults = remember(localDensity) { arrayOfNulls<TextLayoutResult>(127) }
            val trackColor = clip.track?.color ?: primaryColor
            val keyNameTextColor = trackColor.toOnSurfaceColor().copy(0.9F)
            val disabledKeyNameTextStyle = labelMediumStyle.copy(textDecoration = TextDecoration.LineThrough)

            fun NoteMessage.getLayoutResult(offset: Int = 0): TextLayoutResult {
                val results = if (isDisabled) disabledKeyNameLayerResults else keyNameLayerResults
                val curNote = note - offset
                var layoutResult = if (curNote in 0..126) results[curNote] else null
                if (layoutResult == null) {
                    layoutResult = measurer.measure(
                        AnnotatedString(getNoteName(curNote)),
                        if (isDisabled) disabledKeyNameTextStyle else labelMediumStyle,
                        constraints = maxKeyNameSize,
                        density = localDensity
                    )
                    if (curNote in 0..126) results[curNote] = layoutResult
                }
                return layoutResult
            }

            Spacer(Modifier.fillMaxSize().drawWithCache {
                val noteWidthPx = noteWidth.value.toPx()
                val verticalScrollValue = verticalScrollState.value
                val horizontalScrollValue = horizontalScrollState.value
                val noteHeightPx = noteHeight.toPx()
                val shouldDrawNoteName = noteHeightPx >= 13
                val notes = arrayListOf<NoteDrawObject>()
                val backingsNotes = arrayListOf<BackingTrack>()
                action // read mutable state of action

                // get note draw nodes - start
                val startTime = trackClip.time - trackClip.start
                val clip = trackClip.clip
                startNoteIndex = 0
                var flag = true
                clip.notes.read()
                val allNotes = clip.notes.toSet()
                selectedNotes.removeIf { !allNotes.contains(it) }

                val notesInViewList = arrayListOf<NoteMessage>()
                val canvasHeight = size.height
                for ((index, it) in clip.notes.withIndex()) {
                    val y = (KEYBOARD_KEYS - 1 - it.note) * noteHeightPx - verticalScrollValue
                    val x = (startTime + it.time) * noteWidthPx - horizontalScrollValue
                    if (x > size.width) break
                    notesInViewList.add(it)
                    if (y < -noteHeightPx || y > canvasHeight || deletionList.contains(it)) continue
                    val width = it.duration * noteWidthPx
                    if (x < 0 && width < -x) continue
                    if (flag) {
                        startNoteIndex = index
                        flag = false
                    }
                    if (selectedNotes.contains(it)) continue
                    val offset = Offset(x, y.coerceAtLeast(0F))
                    val size = Size(width, if (y < 0) (noteHeightPx + y).coerceAtLeast(0F) else noteHeightPx)
                    val color = trackColor.saturate(it.colorSaturation)
                    notes.add(
                        if (shouldDrawNoteName && width > MIN_NOTE_WIDTH_WITH_KEY_NAME)
                            NoteDrawObject(it, offset, size, color, Offset(x + 3 * density, y), it.getLayoutResult())
                        else NoteDrawObject(it, offset, size, color)
                    )
                }
                notesInView = notesInViewList
                // get note draw nodes - end

                backingTracks.readValue().forEach { (track, _) ->
                    if (track == EchoInMirror.selectedTrack) return@forEach
                    track.clips.read()
                    val color = track.color
                    val curNotes = arrayListOf<NoteDrawObject>()
                    track.clips.fastForEach { clip0 ->
                        val curClip = clip0.asMidiTrackClipOrNull() ?: return@forEach
                        val clipStartTime = curClip.time
                        for (it in curClip.clip.notes) {
                            val y = (KEYBOARD_KEYS - 1 - it.note) * noteHeightPx - verticalScrollValue
                            val x = (clipStartTime + it.time) * noteWidthPx - horizontalScrollValue
                            if (x > size.width) break
                            if (y < -noteHeightPx || y > canvasHeight || deletionList.contains(it)) continue
                            val width = it.duration * noteWidthPx
                            if (x < 0 && width < -x) continue
                            curNotes.add(
                                NoteDrawObject(it, Offset(x, y.coerceAtLeast(0F)), Size(width, if (y < 0)
                                    (noteHeightPx + y).coerceAtLeast(0F) else noteHeightPx),
                                    color.copy(0.6F + 0.4F * mapValue(it.velocity, 0, 127)), null
                                )
                            )
                        }
                    }
                    backingsNotes.add(BackingTrack(track, curNotes))
                }

                val borderCornerRadius2PX = CornerRadius(2F * density, 2F * density)

                onDrawBehind {
                    backingsNotes.fastForEach { cur ->
                        val color = cur.track.color.copy(0.16F)
                        cur.notes.fastForEach { drawRoundRect(color, it.offset, it.size, borderCornerRadius2PX) }
                    }
                    notes.fastForEach {
                        drawRoundRect(it.color, it.offset, it.size, borderCornerRadius2PX)
                        it.keyNameOffset?.let { o -> drawText(it.textLayoutResult!!, keyNameTextColor, o) }
                    }
                    selectedNotes.forEach {
                        var y = (KEYBOARD_KEYS - 1 - it.note) * noteHeightPx - verticalScrollValue
                        val x = (startTime + it.time) * noteWidthPx - horizontalScrollValue
                        val width = it.duration * noteWidthPx
                        val offset: Offset
                        val size: Size
                        var offsetNote = 0
                        when (action) {
                            EditAction.MOVE -> {
                                y += deltaY * noteHeightPx
                                offsetNote = deltaY
                                offset = Offset(x + deltaX * noteWidthPx, y.coerceAtLeast(0F))
                                size = Size(width, if (y < 0) (noteHeightPx + y).coerceAtLeast(0F) else noteHeightPx)
                            }
                            EditAction.RESIZE -> {
                                val height = if (y < 0) (noteHeightPx + y).coerceAtLeast(0F) else noteHeightPx
                                val tmpY = y.coerceAtLeast(0F)
                                offset = if (resizeDirectionRight) Offset(x, tmpY) else Offset(x + deltaX * noteWidthPx, tmpY)
                                size = Size(width + (if (resizeDirectionRight) deltaX else -deltaX) * noteWidthPx, height)
                            }
                            else -> {
                                offset = Offset(x, y.coerceAtLeast(0F))
                                size = Size(width, if (y < 0) (noteHeightPx + y).coerceAtLeast(0F) else noteHeightPx)
                            }
                        }
                        if (size.height <= 0 || size.width <= 0) return@forEach
                        drawRoundRect(trackColor.saturate(it.colorSaturation), offset, size, borderCornerRadius2PX)
                        drawRoundRect(primaryColor, offset, size, borderCornerRadius2PX, Stroke(2f * density))
                        if (shouldDrawNoteName && size.width > MIN_NOTE_WIDTH_WITH_KEY_NAME) {
                            drawText(it.getLayoutResult(offsetNote), keyNameTextColor, Offset(offset.x + 3 * density, y))
                        }
                    }
                }
            })
            notesEditorExtensions.EditorExtensions(false)
            Canvas(Modifier.fillMaxSize().pointerHoverIcon(action.toPointerIcon(cursor))) {
                if (selectionX == 0F && selectionStartX == 0F) return@Canvas
                val scrollX = horizontalScrollState.value
                val scrollY = verticalScrollState.value
                val noteHeightPx = noteHeight.toPx()
                val y = (selectionStartY.coerceAtMost(selectionY).coerceAtMost(KEYBOARD_KEYS - 1) *
                        noteHeightPx - scrollY)
                val pos = Offset(
                    (selectionStartX.coerceAtMost(selectionX) - scrollX).coerceAtLeast(0F),
                    y.coerceAtLeast(0F)
                )
                val size = Size(
                    (selectionX.coerceAtLeast(scrollX.toFloat()) - selectionStartX).absoluteValue,
                    (selectionY.coerceAtLeast((scrollY / noteHeightPx).toInt()) - selectionStartY)
                        .absoluteValue.coerceIn(1, KEYBOARD_KEYS) * noteHeightPx -
                            (if (y < 0) -y % noteHeightPx else 0F)
                )
                drawRect(primaryColor.copy(0.1F), pos, size)
                drawRect(primaryColor, pos, size, style = Stroke(density))
            }
        }
    }
}
