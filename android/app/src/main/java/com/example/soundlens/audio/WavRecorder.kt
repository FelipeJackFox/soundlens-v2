package com.example.soundlens.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

class WavRecorder(private val outputFile: File) {

        private val sampleRate = 44100
        private val channelConfig = AudioFormat.CHANNEL_IN_MONO
        private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        private val bufferSize =
                AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        private var recorder: AudioRecord? = null
        private val isRecording = AtomicBoolean(false)
        private var writerDone: CountDownLatch? = null

        fun startRecording() {
                recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                )
                recorder?.startRecording()
                isRecording.set(true)
                writerDone = CountDownLatch(1)

                thread(name = "WavWriter") {
                        try {
                                val pcmBuffer = ByteArray(bufferSize)
                                FileOutputStream(outputFile).use { fos ->
                                        writeWavHeader(fos, sampleRate, 1, 16, 0)
                                        var totalAudioLen = 0L
                                        while (isRecording.get() && recorder != null) {
                                                val read = recorder!!.read(pcmBuffer, 0, bufferSize)
                                                if (read > 0) {
                                                        fos.write(pcmBuffer, 0, read)
                                                        totalAudioLen += read
                                                }
                                        }
                                        updateWavHeader(outputFile, totalAudioLen)
                                }
                        } catch (_: Exception) {
                        } finally {
                                writerDone?.countDown()
                        }
                }
        }

        /** Detiene y espera a que el hilo cierre el header WAV (evita archivos corruptos). */
        fun stopAndWait(timeoutMs: Long = 2000) {
                isRecording.set(false)
                runCatching {
                        recorder?.apply { stop(); release() }
                }
                recorder = null
                writerDone?.await(timeoutMs, TimeUnit.MILLISECONDS)
                writerDone = null
        }

        fun stopRecording() = stopAndWait()

        private fun writeWavHeader(
                out: FileOutputStream,
                sampleRate: Int,
                channels: Int,
                bitsPerSample: Int,
                totalAudioLen: Long
        ) {
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val totalDataLen = totalAudioLen + 36
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray(Charsets.US_ASCII))
                header.putInt(totalDataLen.toInt())
                header.put("WAVE".toByteArray(Charsets.US_ASCII))
                header.put("fmt ".toByteArray(Charsets.US_ASCII))
                header.putInt(16)
                header.putShort(1)
                header.putShort(channels.toShort())
                header.putInt(sampleRate)
                header.putInt(byteRate)
                header.putShort((channels * bitsPerSample / 8).toShort())
                header.putShort(bitsPerSample.toShort())
                header.put("data".toByteArray(Charsets.US_ASCII))
                header.putInt(totalAudioLen.toInt())
                out.write(header.array(), 0, 44)
        }

        private fun updateWavHeader(file: File, totalAudioLen: Long) {
                val totalDataLen = totalAudioLen + 36
                RandomAccessFile(file, "rw").use { raf ->
                        raf.seek(4);  raf.write(intToLE(totalDataLen.toInt()))
                        raf.seek(40); raf.write(intToLE(totalAudioLen.toInt()))
                }
        }

        private fun intToLE(value: Int): ByteArray =
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
