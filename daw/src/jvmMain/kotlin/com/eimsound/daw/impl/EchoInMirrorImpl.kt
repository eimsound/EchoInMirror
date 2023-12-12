package com.eimsound.daw.impl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eimsound.audioprocessor.*
import com.eimsound.dsp.data.AudioThumbnailCache
import com.eimsound.audioprocessor.data.defaultQuantification
import com.eimsound.audioprocessor.data.getEditUnit
import com.eimsound.daw.AUDIO_THUMBNAIL_CACHE_PATH
import com.eimsound.daw.Configuration
import com.eimsound.daw.actions.AudioProcessorParameterChangeAction
import com.eimsound.daw.api.clips.ClipManager
import com.eimsound.daw.api.EditorTool
import com.eimsound.daw.api.IEchoInMirror
import com.eimsound.daw.api.clips.TrackClip
import com.eimsound.daw.api.processor.Bus
import com.eimsound.daw.api.processor.Track
import com.eimsound.daw.api.processor.TrackManager
import com.eimsound.daw.components.GlobalSnackbarProvider
import com.eimsound.daw.plugin.EIMPluginManager
import com.eimsound.daw.commons.ExperimentalEIMApi
import com.eimsound.daw.window.dialogs.settings.settingsTabsLoader
import com.eimsound.audiosources.AudioSourceManager
import com.eimsound.daw.utils.observableMutableStateOf
import com.microsoft.appcenter.crashes.Crashes
import kotlinx.coroutines.runBlocking

class EchoInMirrorImpl : IEchoInMirror {
    override val currentPosition = PlayPositionImpl(isMainPosition = true)
    override var bus: Bus? by mutableStateOf(null)
    override var player: AudioPlayer? by observableMutableStateOf(null) {
        it?.onClose { player = null }
    }
//    var player: AudioPlayer = JvmAudioPlayer(currentPosition, bus)

    override val commandManager = CommandManagerImpl()
    override val pluginManager = EIMPluginManager()
    override val windowManager = WindowManagerImpl()
    override val audioThumbnailCache by lazy { AudioThumbnailCache(AUDIO_THUMBNAIL_CACHE_PATH) }
    override var quantification by mutableStateOf(defaultQuantification)
    override var editorTool by mutableStateOf(EditorTool.CURSOR)
    override val undoManager = DefaultUndoManager().apply {
        errorHandlers.add(Crashes::trackError)
        errorHandlers.add { GlobalSnackbarProvider.enqueueSnackbar(it) }
        @OptIn(ExperimentalEIMApi::class)
        globalChangeHandler = { list, e -> runBlocking { execute(AudioProcessorParameterChangeAction(list, e)) } }
    }

    private var _selectedTrack by mutableStateOf<Track?>(null)
    override var selectedTrack
        get() = _selectedTrack
        set(value) {
            if (value != _selectedTrack) {
                selectedClip = null
                _selectedTrack = value
            }
        }
    override var selectedClip by mutableStateOf<TrackClip<*>?>(null)

    override fun createAudioPlayer(): AudioPlayer {
        var ret: AudioPlayer? = null
        if (Configuration.audioDeviceFactoryName.isNotEmpty()) {
            try {
                ret = AudioPlayerManager.instance.create(
                    Configuration.audioDeviceFactoryName,
                    Configuration.audioDeviceName, currentPosition, bus!!,
                    if (Configuration.preferredSampleRate > 0) Configuration.preferredSampleRate
                    else null,
                    if (Configuration.preferredBufferSize > 0) Configuration.preferredBufferSize
                    else null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (ret == null) ret = AudioPlayerManager.instance.createDefaultPlayer(currentPosition, bus!!)
        var flag = false
        if (Configuration.audioDeviceFactoryName != ret.factory.name) {
            Configuration.audioDeviceFactoryName = ret.factory.name
            flag = true
        }
        if (Configuration.audioDeviceName != ret.name) {
            Configuration.audioDeviceName = ret.name
            flag = true
        }
        if (Configuration.preferredSampleRate != ret.sampleRate) {
            Configuration.preferredSampleRate = -1
            flag = true
        }
        if (Configuration.preferredBufferSize != ret.bufferSize) {
            Configuration.preferredBufferSize = -1
            flag = true
        }
        if (flag) Configuration.save()
        return ret
    }

    override fun reloadServices() {
        AudioPlayerManager.instance.reload()
        AudioProcessorManager.instance.reload()
        AudioSourceManager.reload()
        ClipManager.instance.reload()
        TrackManager.instance.reload()
        settingsTabsLoader.reload()
    }

    override fun close() {
        player?.close()
        bus?.close()
        player = null
        bus = null
    }

    override val editUnit get() = quantification.getEditUnit(currentPosition)
}
