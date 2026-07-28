package com.workbreaktimer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short announcements played on the alarm stream rather than the notification stream: the
 * phone is face down or pocketed when these fire, and USAGE_ALARM follows alarm volume and
 * sounds in silent mode. Each burst is cut off deliberately — alarm tones run for minutes,
 * and these announce something rather than demand dismissal.
 *
 * State is held here rather than in a service because the services that trigger a chime stop
 * themselves immediately afterwards, which would release the player mid-sound.
 */
object AlarmChime {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Two firm pulses: the timer just took over. */
    val PATTERN_AUTO_START = longArrayOf(0, 300, 150, 300)

    fun play(context: Context, durationMillis: Long, vibrationPattern: LongArray) {
        playTone(context, durationMillis)
        vibrate(context, vibrationPattern)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        release()
    }

    private fun playTone(context: Context, durationMillis: Long) {
        release()
        val appContext = context.applicationContext
        val uri = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return

        val current = MediaPlayer()
        player = current
        current.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        current.setOnCompletionListener { release() }
        try {
            current.setDataSource(appContext, uri)
            current.prepare()
            current.start()
            handler.postDelayed({ release() }, durationMillis)
        } catch (e: Exception) {
            // Playback fails on some devices and emulators; the vibration still announces it.
            release()
        }
    }

    private fun vibrate(context: Context, pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun release() {
        player?.let { current ->
            try { current.stop() } catch (_: Exception) {}
            current.release()
        }
        player = null
    }
}
