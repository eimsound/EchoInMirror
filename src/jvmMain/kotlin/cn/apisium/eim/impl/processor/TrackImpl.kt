package cn.apisium.eim.impl.processor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.apisium.eim.EchoInMirror
import cn.apisium.eim.api.CurrentPosition
import cn.apisium.eim.api.ProjectInformation
import cn.apisium.eim.api.convertPPQToSamples
import cn.apisium.eim.api.processor.*
import cn.apisium.eim.api.processor.dsp.calcPanLeftChannel
import cn.apisium.eim.api.processor.dsp.calcPanRightChannel
import cn.apisium.eim.data.midi.*
import cn.apisium.eim.utils.randomColor
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.pathString

open class TrackImpl(trackName: String) : Track, AbstractAudioProcessor() {
    override var name by mutableStateOf(trackName)
    override var color by mutableStateOf(randomColor(true))
    override var pan by mutableStateOf(0F)
    override var volume by mutableStateOf(1F)

    @JsonIgnore
    override val levelMeter = LevelMeterImpl()
    @JsonIgnore
    override val notes = NoteMessageListImpl()

    @JsonIgnore
    override val preProcessorsChain = mutableStateListOf<AudioProcessor>()
    @JsonIgnore
    override val postProcessorsChain = mutableStateListOf<AudioProcessor>()
    @JsonIgnore
    override val subTracks = mutableStateListOf<Track>()
    private val pendingMidiBuffer = Collections.synchronizedList(ArrayList<Int>())
    private var currentPlayedIndex = 0
    private val pendingNoteOns = LongArray(128)
    private val noteRecorder = MidiNoteRecorder()
    private var lastUpdateTime = 0L

    private var _isMute by mutableStateOf(false)
    private var _isSolo by mutableStateOf(false)
    private var _isDisabled by mutableStateOf(false)
    private var tempBuffer = arrayOf(FloatArray(1024), FloatArray(1024))
    private var tempBuffer2 = arrayOf(FloatArray(1024), FloatArray(1024))
    override var isRendering: Boolean by mutableStateOf(false)
    override var isMute
        get() = _isMute
        set(value) {
            if (_isMute == value) return
            _isMute = value
            stateChange()
        }
    override var isSolo
        get() = _isSolo
        set(value) {
            if (_isSolo == value) return
            _isSolo = value
            stateChange()
        }
    override var isDisabled
        get() = _isDisabled
        set(value) {
            if (_isDisabled == value) return
            _isDisabled = value
            stateChange()
        }

    override suspend fun processBlock(
        buffers: Array<FloatArray>,
        position: CurrentPosition,
        midiBuffer: ArrayList<Int>
    ) {
        if (_isMute || _isDisabled) return
        if (pendingMidiBuffer.isNotEmpty()) {
            midiBuffer.addAll(pendingMidiBuffer)
            pendingMidiBuffer.clear()
        }
        if (position.isPlaying) {
            val blockEndSample = position.timeInSamples + position.bufferSize
            noteRecorder.forEachNotes {
                pendingNoteOns[it] -= position.bufferSize.toLong()
                if (pendingNoteOns[it] <= 0) {
                    noteRecorder.unmarkNote(it)
                    midiBuffer.add(noteOff(0, it).rawData)
                    midiBuffer.add(pendingNoteOns[it].toInt().coerceAtLeast(0))
                }
            }
            for (i in currentPlayedIndex until notes.size) {
                val note = notes[i]
                val startTimeInSamples = position.convertPPQToSamples(note.time)
                val endTimeInSamples = position.convertPPQToSamples(note.time + note.duration)
                if (startTimeInSamples < position.timeInSamples) continue
                if (startTimeInSamples > blockEndSample) break
                currentPlayedIndex = i + 1
                val noteOnTime = (startTimeInSamples - position.timeInSamples).toInt().coerceAtLeast(0)
                if (noteRecorder.isMarked(note.note)) {
                    noteRecorder.unmarkNote(note.note)
                    midiBuffer.add(note.toNoteOffRawData())
                    midiBuffer.add(noteOnTime)
                }
                midiBuffer.add(note.toNoteOnRawData())
                midiBuffer.add(noteOnTime)
                val endTime = endTimeInSamples - position.timeInSamples
                if (endTimeInSamples > blockEndSample) {
                    pendingNoteOns[note.note] = endTime
                    noteRecorder.markNote(note.note)
                } else {
                    midiBuffer.add(note.toNoteOffRawData())
                    midiBuffer.add((endTimeInSamples - position.timeInSamples).toInt().coerceAtLeast(0))
                }
            }
        }
        preProcessorsChain.forEach { it.processBlock(buffers, position, midiBuffer) }
        if (subTracks.size == 1) {
            val track = subTracks.first()
            if (!track.isMute && !track.isDisabled) track.processBlock(buffers, position, ArrayList(midiBuffer))
        } else if (subTracks.isNotEmpty()) {
            tempBuffer[0].fill(0F)
            tempBuffer[1].fill(0F)
            runBlocking {
                subTracks.forEach {
                    if (it.isMute || it.isDisabled || it.isRendering) return@forEach
                    launch {
                        val buffer = if (it is TrackImpl) it.tempBuffer2.apply {
                            buffers[0].copyInto(this[0])
                            buffers[1].copyInto(this[1])
                        } else arrayOf(buffers[0].clone(), buffers[1].clone())
                        it.processBlock(buffer, position, ArrayList(midiBuffer))
                        for (i in 0 until position.bufferSize) {
                            tempBuffer[0][i] += buffer[0][i]
                            tempBuffer[1][i] += buffer[1][i]
                        }
                    }
                }
            }
            tempBuffer[0].copyInto(buffers[0])
            tempBuffer[1].copyInto(buffers[1])
        }
        postProcessorsChain.forEach { it.processBlock(buffers, position, midiBuffer) }

        var leftPeak = 0F
        var rightPeak = 0F
        val leftFactor = calcPanLeftChannel() * volume
        val rightFactor = calcPanRightChannel() * volume
        for (i in buffers[0].indices) {
            buffers[0][i] *= leftFactor
            val tmp = buffers[0][i]
            if (tmp > leftPeak) leftPeak = tmp
        }
        for (i in buffers[1].indices) {
            buffers[1][i] *= rightFactor
            val tmp = buffers[1][i]
            if (tmp > rightPeak) rightPeak = tmp
        }
        levelMeter.left = levelMeter.left.update(leftPeak)
        levelMeter.right = levelMeter.right.update(rightPeak)
        lastUpdateTime += (1000.0 * position.bufferSize / position.sampleRate).toLong()
        if (lastUpdateTime > 300) {
            levelMeter.cachedMaxLevelString = levelMeter.maxLevel.toString()
            lastUpdateTime = 0
        }
    }

    override fun prepareToPlay(sampleRate: Int, bufferSize: Int) {
        tempBuffer = arrayOf(FloatArray(bufferSize), FloatArray(bufferSize))
        tempBuffer2 = arrayOf(FloatArray(bufferSize), FloatArray(bufferSize))
        preProcessorsChain.forEach { it.prepareToPlay(sampleRate, bufferSize) }
        subTracks.forEach { it.prepareToPlay(sampleRate, bufferSize) }
        postProcessorsChain.forEach { it.prepareToPlay(sampleRate, bufferSize) }
    }

    override fun close() {
        EchoInMirror.windowManager.clearTrackUIState(this)
        preProcessorsChain.forEach { it.close() }
        preProcessorsChain.clear()
        subTracks.forEach { it.close() }
        subTracks.clear()
        postProcessorsChain.forEach { it.close() }
        postProcessorsChain.clear()
    }

    override fun playMidiEvent(midiEvent: MidiEvent, time: Int) {
        pendingMidiBuffer.add(midiEvent.rawData)
        pendingMidiBuffer.add(time)
    }

    override fun onSuddenChange() {
        currentPlayedIndex = 0
        stopAllNotes()
        pendingNoteOns.clone()
        noteRecorder.reset()
        preProcessorsChain.forEach(AudioProcessor::onSuddenChange)
        subTracks.forEach(Track::onSuddenChange)
        postProcessorsChain.forEach(AudioProcessor::onSuddenChange)
    }

    override fun stateChange() {
        levelMeter.reset()
        subTracks.forEach(Track::stateChange)
    }

    override fun onRenderStart() {
        isRendering = true
    }

    override fun onRenderEnd() {
        isRendering = false
    }

    override suspend fun save(path: String) {
        withContext(Dispatchers.IO) {
            val dir = Paths.get(path)
            if (!Files.exists(dir)) Files.createDirectory(dir)
            val trackFile = dir.resolve("track.json").toFile()
            jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(trackFile, this@TrackImpl)
        }
    }
}

class BusImpl(override val project: ProjectInformation) : TrackImpl("Bus"), Bus {
    override var channelType by mutableStateOf(ChannelType.STEREO)
    override suspend fun save() {
        project.save()
        save(project.root.pathString)
    }

    override suspend fun processBlock(
        buffers: Array<FloatArray>,
        position: CurrentPosition,
        midiBuffer: ArrayList<Int>
    ) {
        super.processBlock(buffers, position, midiBuffer)

        when (channelType) {
            ChannelType.LEFT -> buffers[0].copyInto(buffers[1])
            ChannelType.RIGHT -> buffers[1].copyInto(buffers[0])
            ChannelType.MONO -> {
                for (i in 0 until position.bufferSize) {
                    buffers[0][i] = (buffers[0][i] + buffers[1][i]) / 2
                    buffers[1][i] = buffers[0][i]
                }
            }
            ChannelType.SIDE -> {
                for (i in 0 until position.bufferSize) {
                    val mid = (buffers[0][i] + buffers[1][i]) / 2
                    buffers[0][i] -= mid
                    buffers[1][i] -= mid
                }
            }
            else -> {}
        }
    }
}