package com.eimsound.daw.impl.clips

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eimsound.daw.api.TrackClip
import com.eimsound.daw.components.NoteWidthSlider
import com.eimsound.daw.components.TIMELINE_HEIGHT
import com.eimsound.daw.components.utils.toOnSurfaceColor
import com.eimsound.daw.window.panels.playlist.playlistTrackControllerMinWidth
import com.eimsound.daw.window.panels.playlist.playlistTrackControllerPanelState

@Composable
fun EditorControls(clip: TrackClip<*>, noteWidth: MutableState<Dp>, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width((playlistTrackControllerPanelState.position / LocalDensity.current.density).dp
                .coerceAtLeast(playlistTrackControllerMinWidth))
    ) {
        val color by animateColorAsState(clip.track?.color ?: MaterialTheme.colorScheme.primary, tween(100))
        Surface(Modifier.fillMaxWidth().height(TIMELINE_HEIGHT), shadowElevation = 2.dp, tonalElevation = 4.dp, color = color) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val textColor by animateColorAsState(color.toOnSurfaceColor(), tween(80))
                Text(clip.clip.name, Modifier.padding(horizontal = 8.dp), textColor, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.padding(10.dp)) {
            NoteWidthSlider(noteWidth)
            content()
        }
    }
}