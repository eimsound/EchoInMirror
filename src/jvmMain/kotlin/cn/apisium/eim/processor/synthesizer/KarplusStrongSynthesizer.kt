package cn.apisium.eim.processor.synthesizer

import cn.apisium.eim.api.CurrentPosition
import cn.apisium.eim.api.processor.AbstractAudioProcessor
import cn.apisium.eim.data.midi.MidiEvent

class KarplusStrongSynthesizer: AbstractAudioProcessor() {
    private val cacheBuffers = FloatArray(1024000)
    private var cacheSize = 0
    private val alpha = 0.995
    private val release = 8.0

    override suspend fun processBlock(buffers: Array<FloatArray>, position: CurrentPosition, midiBuffer: ArrayList<Int>) {
        if (cacheSize > 0) {
            val size = cacheSize.coerceAtMost(buffers[0].size)
            for (i in 0 until size) {
                buffers[0][i] += cacheBuffers[i]
                buffers[1][i] += cacheBuffers[i]
            }
            if (size < cacheSize) {
                for (i in size until cacheSize) {
                    cacheBuffers[i - size] = cacheBuffers[i]
                    cacheBuffers[i] = 0F
                }
            }
            cacheSize -= size
        }
        for (i in 0 until midiBuffer.size step 2) {
            val event = MidiEvent(midiBuffer[i])
            if (!event.isNoteOn) continue
            val noteStartTime = midiBuffer[i + 1]
            val noteLength = (position.sampleRate / event.noteFrequency).toInt()
            val noteData = FloatArray(noteLength)
            for (j in 0 until noteLength) {
                noteData[j] = ((Math.random() * 2 - 1) * event.velocity / 127.0).toFloat()
            }
            val size = (position.sampleRate * release).toInt()
            var cacheSize2 = 0
            for (j in 0 until size) {
                val index = j % noteLength
                val sample = noteData[index]
                noteData[index] = ((sample + noteData[(index + 1) % noteLength]) * alpha / 2).toFloat()
                if (j + noteStartTime < position.bufferSize) {
                    buffers[0][j + noteStartTime] += sample
                    buffers[1][j + noteStartTime] += sample
                } else {
                    cacheBuffers[cacheSize2++] += sample
                }
            }
            if (cacheSize2 > cacheSize) cacheSize = cacheSize2
        }
    }
}
