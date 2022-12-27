package cn.apisium.eim.window.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cn.apisium.eim.EchoInMirror
import cn.apisium.eim.actions.doNoteVelocityAction
import cn.apisium.eim.components.EditorGrid
import cn.apisium.eim.components.KEYBOARD_DEFAULT_WIDTH
import cn.apisium.eim.data.midi.NoteMessage
import cn.apisium.eim.utils.Stroke1_5PX
import kotlin.math.roundToInt

@Composable
internal fun EventEditor() {
    Row {
        Surface(Modifier.width(KEYBOARD_DEFAULT_WIDTH).fillMaxHeight().zIndex(2f), shadowElevation = 5.dp) {

        }
        Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
            val primaryColor = MaterialTheme.colorScheme.primary
            var delta by remember { mutableStateOf(0) }
            var selectedNote by remember { mutableStateOf<NoteMessage?>(null) }
            EditorGrid(noteWidth, horizontalScrollState)
            Spacer(Modifier.fillMaxSize().drawBehind {
                val track = EchoInMirror.selectedTrack ?: return@drawBehind
                val noteWidthPx = noteWidth.toPx()
                val offsetOfDelta = delta / 127F * size.height
                val offsetX = if (action == EditAction.MOVE) deltaX * noteWidthPx else 0f
                val scrollX = horizontalScrollState.value
                notesInView.forEach {
                    val isSelected = selectedNotes.contains(it)
                    val x = it.time * noteWidthPx + scrollX + 2 + (if (isSelected) offsetX else 0f)
                    val y = size.height * it.velocity / 127 + (if (isSelected || selectedNote == it) offsetOfDelta else 0f)
                    drawLine(track.color, Offset(x, y.coerceIn(0f, size.height - 1)), Offset(x, size.height), 4f)
                }
                selectedNotes.forEach {
                    val x = it.time * noteWidthPx + scrollX + offsetX
                    val y = (size.height * it.velocity / 127 + offsetOfDelta).coerceIn(0f, size.height - 1)
                    drawRect(primaryColor, Offset(x, y),
                        Size(4f, size.height - y), style = Stroke1_5PX)
                }
            }.pointerInput(Unit) {
                detectDragGestures({
                    val track = EchoInMirror.selectedTrack ?: return@detectDragGestures
                    val x = it.x + horizontalScrollState.value
                    val noteWidthPx = noteWidth.toPx()
                    for (i in startNoteIndex until track.notes.size) {
                        val note = track.notes[i]
                        val curX = note.time * noteWidthPx
                        if (curX <= x && x <= curX + 4) {
                            @Suppress("ControlFlowWithEmptyBody")
                            if (selectedNotes.isEmpty()) {
                            } else if (!selectedNotes.contains(note)) return@detectDragGestures
                            selectedNote = note
                            break
                        }
                    }
                }, {
                    val cur = selectedNote
                    if (cur != null) {
                        selectedNote = null
                        EchoInMirror.selectedTrack?.doNoteVelocityAction(
                            if (selectedNotes.isEmpty()) arrayOf(cur) else selectedNotes.toTypedArray(), delta)
                    }
                    delta = 0
                }) { it, _ ->
                    val cur = selectedNote ?: return@detectDragGestures
                    delta = (it.position.y / size.height * 127 - cur.velocity).roundToInt().coerceIn(-127, 127)
                }
            })
        }
    }
}
