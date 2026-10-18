package com.workbreaktimer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The workout's sound: numbers spoken by the phone's text-to-speech in the phone's language,
 * and short tones between repetitions. Both play on the media stream, so the volume the user
 * sets for music applies, and speech reaches headphones when they are connected.
 */
class WorkoutVoice(context: Context) : TextToSpeech.OnInitListener {

    enum class Status { LOADING, READY, UNAVAILABLE }

    enum class Tone(val type: Int, val millis: Int) {
        /** A repetition ended, or the first one begins. */
        REP(ToneGenerator.TONE_PROP_PROMPT, 200),
        /** A set ended and rest begins. */
        SET(ToneGenerator.TONE_PROP_ACK, 400),
        /** The whole workout is done. */
        DONE(ToneGenerator.TONE_CDMA_CONFIRM, 1000),
        /** Stands in for a spoken number when no text-to-speech engine works. */
        TICK(ToneGenerator.TONE_PROP_BEEP, 60)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val _status = MutableStateFlow(Status.LOADING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val tts = TextToSpeech(appContext, this)

    private val tones: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    } catch (e: RuntimeException) {
        // Some devices refuse when too many generators exist; speech still works.
        null
    }

    // Requested the way navigation apps do it: music keeps playing, quieter, while numbers
    // are spoken.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { }
        .build()
    private var holdsFocus = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _status.value = Status.UNAVAILABLE
            return
        }
        // A missing language keeps the engine's default voice, which still reads digits.
        tts.setLanguage(Locale.getDefault())
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        // "Четыре" at normal speed takes most of a second; faster keeps each number inside its beat.
        tts.setSpeechRate(1.3f)
        _status.value = Status.READY
    }

    /** Speaks [number], replacing anything still being said so the voice never lags behind. */
    fun say(number: Int, delayMillis: Long = 0) {
        val speak = Runnable {
            if (_status.value == Status.READY) {
                tts.speak(number.toString(), TextToSpeech.QUEUE_FLUSH, null, "count")
            } else if (delayMillis == 0L) {
                tone(Tone.TICK)
            }
        }
        if (delayMillis > 0) handler.postDelayed(speak, delayMillis) else speak.run()
    }

    fun tone(tone: Tone) {
        tones?.startTone(tone.type, tone.millis)
    }

    /** Cuts off speech in progress and any number scheduled to follow a tone. */
    fun silence() {
        handler.removeCallbacksAndMessages(null)
        tts.stop()
    }

    fun acquireFocus() {
        if (holdsFocus) return
        holdsFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun releaseFocus() {
        if (!holdsFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        holdsFocus = false
    }
}
