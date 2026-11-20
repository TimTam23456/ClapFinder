package com.timtam.clapfinder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Makes the "here I am" noise: generated tones on the alarm channel, plus spoken shouts. */
class AlarmPlayer(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Volatile private var playing = false
    @Volatile private var ttsReady = false
    private var thread: Thread? = null
    private var savedVolume = -1
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts?.language = Locale.getDefault()
                    tts?.setAudioAttributes(attrs)
                    ttsReady = true
                } catch (_: Exception) {
                }
            }
        }
    }

    val isPlaying: Boolean get() = playing

    fun play(sound: String, speak: Boolean) {
        if (playing) return
        playing = true
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {
            savedVolume = -1
        }
        thread = Thread { loop(sound, speak) }.also {
            it.name = "alarm-sound"
            it.start()
        }
    }

    fun stop() {
        playing = false
        try {
            thread?.join(600)
        } catch (_: InterruptedException) {
        }
        thread = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        if (savedVolume >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: Exception) {
            }
            savedVolume = -1
        }
    }

    fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    private fun frequency(sound: String, t: Double): Double = when (sound) {
        "beep" -> 2400.0
        "chirp" -> {
            val p = t % 0.5
            if (p < 0.12) 2500.0 + 2500.0 * (p / 0.12) else 2500.0
        }
        "retro" -> RETRO[((t / 0.09).toInt()) % RETRO.size]
        else -> { // siren
            val x = (t % 1.2) / 1.2
            650.0 + 950.0 * (if (x < 0.5) x * 2 else (1 - x) * 2)
        }
    }

    private fun loudness(sound: String, t: Double): Double = when (sound) {
        "beep" -> {
            val p = t % 1.0
            if (p < 0.64 && (p % 0.16) < 0.1) 1.0 else 0.0
        }
        "chirp" -> {
            val p = t % 0.5
            if (p < 0.12) sin(PI * p / 0.12) else 0.0
        }
        "retro" -> if ((t % 0.09) < 0.075) 1.0 else 0.0
        else -> 1.0
    }

    private fun loop(sound: String, speak: Boolean) {
        val sr = 44100
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, sr / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            playing = false
            return
        }
        val chunk = ShortArray(sr / 20)
        var sample = 0L
        var phase = 0.0
        var sinceSpeech = 0.0
        val square = sound == "beep" || sound == "retro"
        try {
            track.play()
            while (playing) {
                if (speak && ttsReady && sinceSpeech >= 4.0) {
                    tts?.speak(PHRASES.random(), TextToSpeech.QUEUE_FLUSH, null, "found")
                    val until = System.currentTimeMillis() + 1900
                    while (playing && System.currentTimeMillis() < until) Thread.sleep(50)
                    sinceSpeech = 0.0
                    continue
                }
                for (i in chunk.indices) {
                    val t = sample.toDouble() / sr
                    phase += 2.0 * PI * frequency(sound, t) / sr
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                    val wave = if (square) (if (sin(phase) >= 0) 0.7 else -0.7) else sin(phase) + 0.3 * sin(3.0 * phase)
                    val v = (wave * loudness(sound, t) * 0.75 * 32767).toInt().coerceIn(-32767, 32767)
                    chunk[i] = v.toShort()
                    sample++
                }
                track.write(chunk, 0, chunk.size)
                sinceSpeech += chunk.size.toDouble() / sr
            }
        } catch (_: Exception) {
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
    }

    companion object {
        private val RETRO = doubleArrayOf(523.25, 659.25, 783.99, 1046.5, 783.99, 659.25)
        private val PHRASES = listOf("I'm over here!", "Here I am!", "Over here!", "Hey! I'm right here!")
    }
}
