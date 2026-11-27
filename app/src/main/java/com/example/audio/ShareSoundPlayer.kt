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
        const val CHIME_DURATION_MS = 4250L
    }

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val playerLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var completionPlayer: MediaPlayer? = null
    private var fallbackAudioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var isSweepPlaying: Boolean = false

    @Volatile
    private var sweepDurationMs: Long = CHIME_DURATION_MS

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

        var createdPlayer: MediaPlayer? = null
        val started = runCatching {
            val afd = appContext.resources.openRawResourceFd(R.raw.namedrop_chime)
            val player = MediaPlayer()
            createdPlayer = player
            player.apply {
                setAudioAttributes(mediaAttributes)
                afd.use { descriptor ->
                    setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                }
                prepare()
                setVolume(1.0f, 1.0f)
                setOnCompletionListener { mp ->
                    synchronized(playerLock) {
                        if (mediaPlayer === mp) {
                            mediaPlayer = null
                            isSweepPlaying = false
                        }
                    }
                    releasePlayerSafely(mp)
                    abandonAudioFocus()
                }
                setOnErrorListener { mp, what, extra ->
                    DebugLogger.log("Audio", "MediaPlayer error $what/$extra — starting PCM fallback", "ERROR")
                    synchronized(playerLock) {
                        if (mediaPlayer === mp) {
                            mediaPlayer = null
                            isSweepPlaying = false
                        }
                    }
                    releasePlayerSafely(mp)
                    playFallbackPcmChime()
                    true
                }
            }
            val durationMs = player.duration.toLong().takeIf { it > 0L } ?: CHIME_DURATION_MS
            synchronized(playerLock) {
                mediaPlayer = player
                sweepDurationMs = durationMs
                playbackStartElapsedMs = SystemClock.elapsedRealtime()
                isSweepPlaying = true
                player.start()
            }
            DebugLogger.log(
                "Audio",
                "Playing Gemini+ApplePay sweep chime (${durationMs}ms, streamVol=${audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)})"
            )
            true
        }.onFailure {
            synchronized(playerLock) {
                isSweepPlaying = false
                if (mediaPlayer === createdPlayer) {
                    mediaPlayer = null
                }
            }
            releasePlayerSafely(createdPlayer)
            DebugLogger.log("Audio", "MediaPlayer failed: ${it.message} — using PCM fallback", "ERROR")
        }.getOrDefault(false)

        if (!started) {
            playFallbackPcmChime()
        }
    }

    fun playCompletionPing() {
        val previous = synchronized(playerLock) {
            val old = completionPlayer
            completionPlayer = null
            old
        }
        releasePlayerSafely(previous)
        requestTransientAudioFocus()

        var createdPlayer: MediaPlayer? = null
        runCatching {
            val afd = appContext.resources.openRawResourceFd(R.raw.applepay_chime)
            val player = MediaPlayer()
            createdPlayer = player
            player.apply {
                setAudioAttributes(mediaAttributes)
                afd.use { descriptor ->
                    setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                }
                prepare()
                setVolume(1.0f, 1.0f)
                setOnCompletionListener { mp ->
                    synchronized(playerLock) {
                        if (completionPlayer === mp) completionPlayer = null
                    }
                    releasePlayerSafely(mp)
                    abandonAudioFocus()
                }
                setOnErrorListener { mp, _, _ ->
                    synchronized(playerLock) {
                        if (completionPlayer === mp) completionPlayer = null
                    }
                    releasePlayerSafely(mp)
                    abandonAudioFocus()
                    true
                }
            }
            synchronized(playerLock) {
                completionPlayer = player
                player.start()
            }
        }.onFailure {
            synchronized(playerLock) {
                if (completionPlayer === createdPlayer) completionPlayer = null
            }
            releasePlayerSafely(createdPlayer)
            DebugLogger.log("Audio", "Completion ping failed: ${it.message}", "ERROR")
        }
    }

    /**
     * Returns the current playback position in milliseconds, using the active MediaPlayer
     * audio clock while actively playing and falling back smoothly to elapsed wall-clock time.
     */
    fun getCurrentPositionMs(): Long {
        val elapsed = (SystemClock.elapsedRealtime() - playbackStartElapsedMs).coerceAtLeast(0L)
        if (!isSweepPlaying || elapsed >= sweepDurationMs - 80L) {
            return elapsed
        }
        return runCatching {
            synchronized(playerLock) {
                val mp = mediaPlayer
                if (mp != null && isSweepPlaying) {
                    mp.currentPosition.toLong().coerceAtLeast(0L)
                } else {
                    elapsed
                }
            }
        }.getOrDefault(elapsed)
    }

    private fun releasePlayerSafely(player: MediaPlayer?) {
        if (player == null) return
        runCatching { player.release() }
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
                val totalSamples = (sampleRate * 4.25f).toInt()
                val pcm = ShortArray(totalSamples)

                for (i in 0 until totalSamples) {
                    val t = i / sampleRate.toDouble()
                    var s = 0.0
                    // Apple Pay style burst ping around 0.78s
                    if (t >= 0.78 && t < 1.55) {
                        val dt = t - 0.78
                        val env = exp(-dt * 4.5) * (1.0 - exp(-dt * 40.0)) * 0.45
                        s += (sin(2.0 * PI * 1046.50 * t) + 0.55 * sin(2.0 * PI * 1567.98 * t)) * env
                    }
                    // Gemini swell across star rise, flash, and sweep (1.35s - 4.15s)
                    if (t >= 1.35) {
                        val dt = t - 1.35
                        val env = exp(-dt * 1.45) * (1.0 - exp(-dt * 14.0)) * 0.45
                        s += (sin(2.0 * PI * 659.25 * t) + 0.6 * sin(2.0 * PI * 987.77 * t)) * env
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
        val (sweepMp, pingMp, track) = synchronized(playerLock) {
            isSweepPlaying = false
            val s = mediaPlayer
            val p = completionPlayer
            val t = fallbackAudioTrack
            mediaPlayer = null
            completionPlayer = null
            fallbackAudioTrack = null
            Triple(s, p, t)
        }
        releasePlayerSafely(sweepMp)
        releasePlayerSafely(pingMp)

        runCatching {
            track?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
                it.release()
            }
        }
        abandonAudioFocus()
    }
}
