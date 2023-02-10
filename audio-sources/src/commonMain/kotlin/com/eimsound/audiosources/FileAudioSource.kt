package com.eimsound.audiosources

import com.eimsound.audioprocessor.AudioSource
import com.eimsound.audioprocessor.FileAudioSource
import com.eimsound.audioprocessor.FileAudioSourceFactory
import com.fasterxml.jackson.databind.JsonNode
import org.jflac.FLACDecoder
import org.jflac.io.RandomFileInputStream
import org.jflac.sound.spi.FlacFileFormatType
import org.jflac.util.ByteData
import org.jflac.util.RingBuffer
import org.tritonus.sampled.file.WaveAudioFileReader
import org.tritonus.share.sampled.FloatSampleTools
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.absoluteValue

private const val MB500 = 500 * 1024 * 1024

class DefaultFileAudioSource(override val factory: FileAudioSourceFactory<*>, override val file: Path) : FileAudioSource {
    private val format = AudioSystem.getAudioFileFormat(file.toFile())
    private val isWav = format.type == AudioFileFormat.Type.WAVE
    private val isFlac = format.type == FlacFileFormatType.FLAC

    override val source: AudioSource? = null
    override val sampleRate = format.format.sampleRate
    override val channels = format.format.channels
    override val length = format.frameLength.toLong()
    override val isRandomAccessible = isWav || isFlac

    private val frameSize = channels * (format.format.sampleSizeInBits / 8)
    private val newFormat = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, format.format.sampleSizeInBits,
        channels, frameSize, sampleRate, false)

    private var stream: InputStream? = null
    private lateinit var flacDecoder: FLACDecoder
    private var lastPos: Long = 0
    private var pcmData: ByteData? = null
    private var tempBuffer: ByteArray? = null
    private lateinit var buffer: RingBuffer

    init {
        if (isWav) {
            stream = WaveAudioFileReader().getAudioInputStream(RandomFileInputStream(file.toFile())).apply { mark(0) }
        } else if (isFlac) {
            stream = RandomFileInputStream(file.toFile())
            buffer = RingBuffer()
            buffer.resize(frameSize * 2)
            flacDecoder = FLACDecoder(stream).apply { readMetadata() }
        } else {
            if (length * frameSize > MB500) throw UnsupportedOperationException("File is large than 500mb!")
            try {
                stream = AudioSystem.getAudioInputStream(BufferedInputStream(FileInputStream(file.toFile())))
            } catch (e: Exception) {
                throw UnsupportedOperationException(e)
            }
        }
    }

    override fun getSamples(start: Long, buffers: Array<FloatArray>): Int {
        val stream = stream ?: return 0
        var consumed = 0
        if (isFlac) {
            if ((lastPos + buffers[0].size - start).absoluteValue > 4) {
                flacDecoder.seek(start.coerceIn(0, length))
                buffer.empty()
            }
            readFromByteArray(buffers, this::readFlac)
            consumed = buffers[0].size
            lastPos = start
        } else {
            val len = length
            if (start > len) return 0
            if (lastPos + buffers[0].size != start) {
                if (start > lastPos) {
                    stream.skip((start - lastPos) * frameSize)
                } else {
                    stream.reset()
                    stream.skip(start * frameSize)
                }
            }
            for (i in 0 until channels) {
                if (i >= buffers.size) break
                consumed = buffers[i].size.coerceAtMost((len - start).toInt())
                readFromByteArray(buffers) { offset, l -> stream.read(tempBuffer!!, offset, l) }
            }
            lastPos = start
        }
        return consumed
    }

    override fun close() {
        stream?.close()
        stream = null
    }

    private fun readFlac(offset: Int, len: Int): Int {
        var offset2 = offset
        var len2 = len
        var bytesRead = 0
        // can only read integral number of frames
        len2 -= len2 % frameSize
        // do the best effort to fill the buffer
        while (len2 > 0) {
            var thisLen = len2
            if (thisLen > buffer.available) thisLen = buffer.available
            if (thisLen < frameSize) {
                val frame = flacDecoder.readNextFrame() ?: break
                val data = flacDecoder.decodeFrame(frame, pcmData)
                pcmData = data
                buffer.resize(data.len * 2)
                buffer.put(data.data, 0, data.len)

                if (buffer.available < frameSize) break
                continue
            }
            // can only read integral number of frames
            thisLen -= (thisLen % frameSize)
            val thisBytesRead = buffer.get(tempBuffer, offset2, thisLen)
            if (thisBytesRead < frameSize) break
            offset2 += thisBytesRead
            len2 -= thisBytesRead
            bytesRead += thisBytesRead
        }
        return if (bytesRead < 1) -1 else bytesRead
    }

    private inline fun readFromByteArray(buffers: Array<FloatArray>, block: (Int, Int) -> Int) {
        // read into temporary byte buffer
        val sampleCount = buffers[0].size
        var byteBufferSize = sampleCount * frameSize
        var lTempBuffer = tempBuffer
        if (lTempBuffer == null || byteBufferSize > lTempBuffer.size) {
            lTempBuffer = ByteArray(byteBufferSize)
            tempBuffer = lTempBuffer
        }
        var readSamples = 0
        var byteOffset = 0
        while (readSamples < sampleCount) {
            val readBytes = block(byteOffset, byteBufferSize)
            if (readBytes < 0) {
                break
            } else if (readBytes == 0) {
                Thread.yield()
            } else {
                readSamples += readBytes / frameSize
                byteBufferSize -= readBytes
                byteOffset += readBytes
            }
        }
        // buffer.setSampleCount(offset + readSamples, offset > 0)
        if (readSamples > 0) {
            // convert
            FloatSampleTools.byte2float(
                lTempBuffer, 0, buffers, 0,
                readSamples, newFormat, false
            )
        }
    }
}

class DefaultFileAudioSourceFactory : FileAudioSourceFactory<DefaultFileAudioSource> {
    override val supportedFormats = listOf("wav", "flac", "mp3", "ogg", "aiff", "aif", "aifc", "au", "snd")
    override val name = "File"
    override fun createAudioSource(file: File) = DefaultFileAudioSource(this, file.toPath())
    override fun createAudioSource(source: AudioSource?, json: JsonNode?): DefaultFileAudioSource {
        val file = json?.get("file")?.asText() ?: throw IllegalArgumentException("File not found!")
        return createAudioSource(File(file))
    }
}
