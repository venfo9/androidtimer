package com.workbreaktimer.app

import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Counts steps while a work phase runs. Steps add up into a series while the user keeps
 * moving; a pause longer than the configured gap clears the series. Every time the series
 * reaches the configured number of steps, the sitting countdown restarts and the series
 * starts over, so a long walk keeps resetting the timer.
 *
 * Step times come from the sensor event timestamps rather than delivery time: with the
 * screen off many phones deliver steps in batches, and the timestamps still say when each
 * step happened.
 *
 * Shares the countdown notification (TimerCountdownNotifier) as its foreground notification,
 * so a running work phase shows one entry in the shade, not two.
 */
class WalkResetService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val gapCheck = Runnable { checkSeriesEnded() }

    /** Fallback for phones without a step detector: the cumulative counter, -1 until read. */
    private var lastCounterValue = -1f

    /** Sensor-clock (elapsed realtime) nanos of the latest step; -1 before the first. */
    private var lastStepNanos = -1L
    private var seriesStartNanos = -1L

    /** Steps last written to the notification, rounded down to [NOTIFICATION_STEP]. */
    private var shownSteps = -1

    companion object {
        private const val NOTIFICATION_STEP = 10
        private val RESET_VIBRATION = longArrayOf(0, 120, 100, 120, 100, 120)

        /** Line the countdown notification shows while this service runs; null when stopped. */
        @Volatile
        var statusLine: String? = null
            private set

        private val _seriesSteps = MutableStateFlow(0)

        /** Steps in the current series, for the progress bar on the timer screen. */
        val seriesSteps: StateFlow<Int> = _seriesSteps.asStateFlow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TimerManager.init(this)
        val state = TimerManager.state.value
        if (statusLine == null) statusLine = progressLine(_seriesSteps.value)
        // Called before any early exit: a service started with startForegroundService has to
        // reach startForeground or the system kills the app.
        startForeground(TimerCountdownNotifier.NOTIFICATION_ID, TimerCountdownNotifier.notification(this, state))
        if (!state.watchesWalking || !TimerManager.hasActivityRecognitionPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (sensorManager == null) registerSensor()
        return START_STICKY
    }

    private fun registerSensor() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            // No pedometer: the feature cannot work here, so turn it off rather than pretend.
            TimerManager.updateSettings(this) { it.copy(walkResetEnabled = false) }
            stopSelf()
            return
        }
        sensorManager = sm
        // maxReportLatencyUs = 0 asks for steps as they happen instead of in batches.
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> onSteps(1, event.timestamp)
            Sensor.TYPE_STEP_COUNTER -> {
                val count = event.values.firstOrNull() ?: return
                val previous = lastCounterValue
                lastCounterValue = count
                // The first reading is the total since boot, a baseline rather than steps.
                if (previous >= 0f && count > previous) onSteps((count - previous).toInt(), event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onSteps(steps: Int, stepNanos: Long) {
        val settings = TimerManager.state.value.settings
        val gapNanos = settings.walkGapSeconds * 1_000_000_000L
        var series = _seriesSteps.value
        if (series == 0 || stepNanos - lastStepNanos > gapNanos) {
            series = 0
            seriesStartNanos = stepNanos
        }
        lastStepNanos = maxOf(lastStepNanos, stepNanos)
        series += steps

        if (series >= settings.walkResetSteps) {
            series = 0
            vibrate()
            TimerManager.restartWorkAfterWalk(this, toWallClock(seriesStartNanos))
            // A walk that goes on keeps counting from here toward the next restart.
            seriesStartNanos = stepNanos
        }
        publish(series)

        handler.removeCallbacks(gapCheck)
        handler.postDelayed(gapCheck, settings.walkGapSeconds * 1000L)
    }

    /** Clears the series once the gap passes with no step; a late batch may still extend it. */
    private fun checkSeriesEnded() {
        if (_seriesSteps.value == 0) return
        val gapNanos = TimerManager.state.value.settings.walkGapSeconds * 1_000_000_000L
        val sinceLastStep = SystemClock.elapsedRealtimeNanos() - lastStepNanos
        if (sinceLastStep < gapNanos) {
            handler.postDelayed(gapCheck, (gapNanos - sinceLastStep) / 1_000_000L + 1)
            return
        }
        publish(0)
    }

    private fun publish(series: Int) {
        _seriesSteps.value = series
        val rounded = series / NOTIFICATION_STEP * NOTIFICATION_STEP
        if (rounded == shownSteps) return
        shownSteps = rounded
        statusLine = progressLine(rounded)
        TimerCountdownNotifier.sync(this, TimerManager.state.value)
    }

    private fun progressLine(steps: Int): String {
        val target = TimerManager.state.value.settings.walkResetSteps
        return if (steps == 0) "Идёт отсчёт · педометр следит" else "Шаги до сброса: $steps из $target"
    }

    private fun toWallClock(sensorNanos: Long): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - sensorNanos) / 1_000_000L

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(RESET_VIBRATION, -1))
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        handler.removeCallbacksAndMessages(null)
        statusLine = null
        _seriesSteps.value = 0
        // Removing the foreground notification takes the countdown with it; post it again if
        // the timer is still paused or running without this service.
        stopForeground(STOP_FOREGROUND_REMOVE)
        TimerCountdownNotifier.sync(this, TimerManager.state.value)
        super.onDestroy()
    }
}
