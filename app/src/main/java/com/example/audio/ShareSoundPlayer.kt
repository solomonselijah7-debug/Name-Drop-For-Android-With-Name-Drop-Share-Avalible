package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import com.example.R
import com.example.data.DebugLogger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Plays the bundled Gemini Startup + Apple Pay NameDrop composite chime (R.raw.namedrop_chime)
 * on the Media stream (USAGE_MEDIA) with transient audio focus so it remains audible even when
 * the device is on Vibrate/Silent or System Touch Sounds are muted, and provides live audio-clock
 * position polling so the 6-stage perimeter sweep stays frame-locked to actual sound playback.
 */
class ShareSoundPlayer(private val context: Context) {

    companion object {
        const val CHIME_DURATION_MS = 3150L
    }

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var completionPlayer: MediaPlayer? = null
    private var fallbackAudioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var playbackStartElapsedMs: Long = 0L

    fun isMediaVolumeMuted(): Boolean {
        val am = audioManager ?: return false
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }

    fun playSweepSyncedChime() {
        stop()
        playbackStartElapsedMs = SystemClock.elapsedRealtime()
        requestTransientAudioFocus()

        val started = runCatching {
            val afd = appContext.resources.openRawResourceFd(R.raw.namedrop_chime)
            val player = MediaPlayer().apply {
                setAudioAttributes(mediaAttributes)
                afd.use { descriptor ->
                    setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                }
                prepare()
                setVolume(1.0f, 1.0f)
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
                    }
                    abandonAudioFocus()
                }
                setOnErrorListener { mp, what, extra ->
                    DebugLogger.log("Audio", "MediaPlayer error $what/$extra — starting PCM fallback", "ERROR")
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) mediaPlayer = null
                    playFallbackPcmChime()
                    true
                }
            }
            mediaPlayer = player
            playbackStartElapsedMs = SystemClock.elapsedRealtime()
            player.start()
            DebugLogger.log(
                "Audio",
                "Playing Gemini+ApplePay sweep chime (${player.duration}ms, streamVol=${audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)})"
            )
            true
        }.onFailure {
            DebugLogger.log("Audio", "MediaPlayer failed: ${it.message} — using PCM fallback", "ERROR")
        }.getOrDefault(false)

        if (!started) {
            playFallbackPcmChime()
        }
    }

    fun playCompletionPing() {
        runCatching {
            completionPlayer?.let { prev ->
                if (prev.isPlaying) prev.stop()
                prev.release()
            }
        }
        completionPlayer = null
        requestTransientAudioFocus()

        runCatching {
            val afd = appContext.resources.openRawResourceFd(R.raw.applepay_chime)
            val player = MediaPlayer().apply {
                setAudioAttributes(mediaAttributes)
                afd.use { descriptor ->
                    setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                }
                prepare()
                setVolume(1.0f, 1.0f)
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (completionPlayer === mp) completionPlayer = null
                    abandonAudioFocus()
                }
                setOnErrorListener { mp, _, _ ->
                    runCatching { mp.release() }
                    if (completionPlayer === mp) completionPlayer = null
                    abandonAudioFocus()
                    true
                }
            }
            completionPlayer = player
            player.start()
        }.onFailure {
            DebugLogger.log("Audio", "Completion ping failed: ${it.message}", "ERROR")
        }
    }

    /**
     * Returns the current playback position in milliseconds, using the active MediaPlayer
     * audio clock when playing and falling back smoothly to elapsed wall-clock time.
     */
    fun getCurrentPositionMs(): Long {
        val elapsed = (SystemClock.elapsedRealtime() - playbackStartElapsedMs).coerceAtLeast(0L)
        val mp = mediaPlayer ?: return elapsed
        return runCatching {
            if (mp.isPlaying) {
                mp.currentPosition.toLong().coerceAtLeast(0L)
            } else {
                elapsed
            }
        }.getOrDefault(elapsed)
    }

    private fun requestTransientAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(mediaAttributes)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    private fun playFallbackPcmChime() {
        thread(isDaemon = true, name = "chime-fallback") {
            runCatching {
                val sampleRate = 44100
                val totalSamples = (sampleRate * 3.15f).toInt()
                val pcm = ShortArray(totalSamples)

                for (i in 0 until totalSamples) {
                    val t = i / sampleRate.toDouble()
                    var s = 0.0
                    // Opening shimmer (0.0s - 0.8s)
                    if (t < 0.85) {
                        val env = sin(PI * (t / 0.85)).coerceAtLeast(0.0) * 0.25
                        s += sin(2.0 * PI * 523.25 * t) * env
                    }
                    // Main harmonic swell (0.85s peak -> 1.50s crest)
                    if (t >= 0.80) {
                        val dt = t - 0.80
                        val env = exp(-dt * 2.2) * (1.0 - exp(-dt * 18.0)) * 0.45
                        s += (sin(2.0 * PI * 659.25 * t) + 0.6 * sin(2.0 * PI * 987.77 * t)) * env
                    }
                    // Third crisp chime ping (1.85s)
                    if (t >= 1.85) {
                        val dt = t - 1.85
                        val env = exp(-dt * 2.6) * (1.0 - exp(-dt * 28.0)) * 0.50
                        s += (sin(2.0 * PI * 1046.50 * t) + 0.5 * sin(2.0 * PI * 1567.98 * t)) * env
                    }
                    pcm[i] = (s.coerceIn(-0.95, 0.95) * Short.MAX_VALUE).toInt().toShort()
                }

                val bufferBytes = pcm.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(mediaAttributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                fallbackAudioTrack = track
                track.write(pcm, 0, pcm.size)
                track.setVolume(1.0f)
                track.play()
                Thread.sleep(CHIME_DURATION_MS + 100L)
                runCatching {
                    track.stop()
                    track.release()
                }
                if (fallbackAudioTrack === track) fallbackAudioTrack = null
                abandonAudioFocus()
            }
        }
    }

    fun stop() {
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        mediaPlayer = null

        runCatching {
            completionPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        completionPlayer = null

        runCatching {
            fallbackAudioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.release()
            }
        }
        fallbackAudioTrack = null
        abandonAudioFocus()
    }
}
