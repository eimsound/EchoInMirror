package cn.apisium.eim.impl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.apisium.eim.EchoInMirror
import cn.apisium.eim.api.CurrentPosition
import cn.apisium.eim.api.Track
import cn.apisium.eim.api.processor.AbstractAudioProcessor
import cn.apisium.eim.api.processor.AudioProcessor
import cn.apisium.eim.api.processor.LevelMeterImpl
import cn.apisium.eim.api.processor.dsp.calcPanLeftChannel
import cn.apisium.eim.api.processor.dsp.calcPanRightChannel
import cn.apisium.eim.data.midi.MidiEvent
import cn.apisium.eim.data.midi.MidiNoteRecorder
import cn.apisium.eim.data.midi.NoteMessage
import cn.apisium.eim.data.midi.noteOff
import cn.apisium.eim.utils.randomColor
import java.util.*
import kotlin.collections.ArrayList

open class TrackImpl(
    trackName: String
) : Track, AbstractAudioProcessor() {
    override var name by mutableStateOf(trackName)
    override var color by mutableStateOf(randomColor(true))
    override var pan by mutableStateOf(0F)
    override var volume by mutableStateOf(1F)

    override val levelMeter = LevelMeterImpl()
    override val notes: MutableList<NoteMessage> = mutableStateListOf()

    override val preProcessorsChain = mutableStateListOf<AudioProcessor>()
    override val postProcessorsChain = mutableStateListOf<AudioProcessor>()
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

    override var isMute get() = _isMute
        set(value) {
            if (_isMute == value) return
            _isMute = value
            stateChange()
        }
    override var isSolo get() = _isSolo
        set(value) {
            if (_isSolo == value) return
            _isSolo = value
            stateChange()
        }
    override var isDisabled get() = _isDisabled
        set(value) {
            if (_isDisabled == value) return
            _isDisabled = value
            stateChange()
        }

    override suspend fun processBlock(buffers: Array<FloatArray>, position: CurrentPosition, midiBuffer: ArrayList<Int>) {
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
                if (noteRecorder.isMarked(note.note.note)) {
                    noteRecorder.unmarkNote(note.note.note)
                    midiBuffer.add(note.note.toNoteOff().rawData)
                    midiBuffer.add(noteOnTime)
                }
                midiBuffer.add(note.note.rawData)
                midiBuffer.add(noteOnTime)
                val endTime = endTimeInSamples - position.timeInSamples
                if (endTimeInSamples > blockEndSample) {
                    pendingNoteOns[note.note.note] = endTime
                    noteRecorder.markNote(note.note.note)
                } else {
                    midiBuffer.add(note.note.toNoteOff().rawData)
                    midiBuffer.add((endTimeInSamples - position.timeInSamples).toInt().coerceAtLeast(0))
                }
            }
        }
        preProcessorsChain.forEach { it.processBlock(buffers, position, midiBuffer) }
        subTracks.filter { !it.isDisabled && !it.isMute }.let { tracks ->
            if (tracks.size == 1) tracks.first().processBlock(buffers, position, ArrayList(midiBuffer))
            else if (tracks.isNotEmpty()) {
                buffers[0].copyInto(tempBuffer[0])
                buffers[1].copyInto(tempBuffer[1])
                tracks.first().processBlock(buffers, position, ArrayList(midiBuffer))
                for (j in 2 until tracks.size) {
                    tempBuffer[0].copyInto(tempBuffer2[0])
                    tempBuffer[1].copyInto(tempBuffer2[1])
                    tracks[j].processBlock(tempBuffer2, position, ArrayList(midiBuffer))
                    for (i in 0 until position.bufferSize) {
                        buffers[0][i] += tempBuffer2[0][i]
                        buffers[1][i] += tempBuffer2[1][i]
                    }
                }
                tracks[1].processBlock(tempBuffer, position, ArrayList(midiBuffer))
                for (i in 0 until position.bufferSize) {
                    buffers[0][i] += tempBuffer[0][i]
                    buffers[1][i] += tempBuffer[1][i]
                }
            }
        }
        postProcessorsChain.forEach { it.processBlock(buffers, position, midiBuffer) }
        var leftPeak = 0F
        var rightPeak = 0F
        for (i in buffers[0].indices) {
            buffers[0][i] *= calcPanLeftChannel() * volume
            val tmp = buffers[0][i]
            if (tmp > leftPeak) leftPeak = tmp
        }
        for (i in buffers[1].indices) {
            buffers[1][i] *= calcPanRightChannel() * volume
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

    override fun prepareToPlay() {
        val size = EchoInMirror.currentPosition.bufferSize
        tempBuffer = arrayOf(FloatArray(size), FloatArray(size))
        preProcessorsChain.forEach(AudioProcessor::prepareToPlay)
        subTracks.forEach(Track::prepareToPlay)
        postProcessorsChain.forEach(AudioProcessor::prepareToPlay)
    }

    override fun close() {
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
}
