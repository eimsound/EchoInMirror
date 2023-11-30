package com.eimsound.daw.window.dialogs.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastForEach
import com.eimsound.audioprocessor.AudioPlayerManager
import com.eimsound.daw.Configuration
import com.eimsound.daw.api.EchoInMirror
import com.eimsound.daw.components.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.commons.lang3.SystemUtils
import kotlin.math.roundToInt

@Composable
private fun Latency(title: String, latency: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Text(
            "${(latency * 1000.0 / EchoInMirror.currentPosition.sampleRate).roundToInt()} 毫秒 / $latency 个采样",
            Modifier.weight(1f)
        )
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun reopenAudioDevice() {
    GlobalScope.launch(Dispatchers.IO) {
        Configuration.save()
        EchoInMirror.player = EchoInMirror.createAudioPlayer()
    }
}

internal object AudioSettings : SettingTab {
    @Composable
    override fun label() {
        Text("音频")
    }

    @Composable
    override fun icon() {
        Icon(Icons.Filled.SettingsInputComponent, "Audio Settings")
    }

    @Composable
    override fun content() {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音频工厂:", Modifier.weight(1f))
                Menu({ close ->
                    AudioPlayerManager.instance.factories.forEach { (name, _) ->
                        MenuItem({
                            close()
                            if (Configuration.audioDeviceFactoryName == name) return@MenuItem
                            EchoInMirror.player?.close()
                            Configuration.audioDeviceName = ""
                            Configuration.audioDeviceFactoryName = name

                            reopenAudioDevice()
                        }, Configuration.audioDeviceFactoryName == name, modifier = Modifier.fillMaxWidth()) {
                            Text(name)
                        }
                    }
                }, boxModifier = Modifier.weight(1f)) {
                    Text(Configuration.audioDeviceFactoryName, Modifier.fillMaxWidth())
                }
            }
            Gap(8)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音频设备:", Modifier.weight(1f))
                Menu({ close ->
                    var playerNames by remember { mutableStateOf(emptyList<String>()) }
                    LaunchedEffect(Configuration.audioDeviceName) {
                        playerNames = AudioPlayerManager.instance.factories[Configuration.audioDeviceFactoryName]!!.getPlayers()
                    }
                    playerNames.fastForEach { playerName ->
                        MenuItem({
                            close()
                            if (Configuration.audioDeviceName == playerName) return@MenuItem
                            EchoInMirror.player?.close()
                            Configuration.audioDeviceName = playerName

                            reopenAudioDevice()
                        }, Configuration.audioDeviceName == playerName, modifier = Modifier.fillMaxWidth()) {
                            Text(playerName)
                        }
                    }
                }, boxModifier = Modifier.weight(1f)) {
                    Text(Configuration.audioDeviceName, Modifier.fillMaxWidth())
                }
            }
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("缓冲区大小:", Modifier.weight(1f))
                Menu({ close ->
                    EchoInMirror.player?.availableBufferSizes?.forEach {
                        MenuItem({
                            close()
                            EchoInMirror.player?.close()

                            reopenAudioDevice()
                        }, EchoInMirror.currentPosition.bufferSize == it, modifier = Modifier.fillMaxWidth()) {
                            Text("$it 个采样")
                        }
                    }
                }, boxModifier = Modifier.weight(1f)) {
                    Text("${EchoInMirror.currentPosition.bufferSize} 个采样", Modifier.fillMaxWidth())
                }
            }

            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("采样率:", Modifier.weight(1f))
                Menu({ close ->
                    val sampleRate = EchoInMirror.player?.sampleRate
                    EchoInMirror.player?.availableSampleRates?.fastForEach {
                        MenuItem({
                            close()
                            EchoInMirror.player?.close()
                            Configuration.preferredSampleRate = it

                            reopenAudioDevice()
                        }, sampleRate == it, modifier = Modifier.fillMaxWidth()) {
                            Text(it.toString())
                        }
                    }
                }, boxModifier = Modifier.weight(1f)) {
                    Text(EchoInMirror.player?.sampleRate?.toString() ?: "未知", Modifier.fillMaxWidth())
                }
            }

            if (SystemUtils.IS_OS_WINDOWS) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("后台共享音频设备")
                Checkbox(
                    Configuration.stopAudioOutputOnBlur,
                    {
                        Configuration.stopAudioOutputOnBlur = it
                        Configuration.save()
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("对超过 0db 的音频进行削波")
                Checkbox(
                    Configuration.autoCutOver0db,
                    {
                        Configuration.autoCutOver0db = it
                        Configuration.save()
                    }
                )
            }

            Latency("输入延迟", EchoInMirror.player?.inputLatency ?: 0)
            Latency("输出延迟", EchoInMirror.player?.outputLatency ?: 0)
            Gap(8)
            EchoInMirror.player?.Controls()
        }
    }
}