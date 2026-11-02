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

/**
 * Listens to the pedometer while a work phase runs. A walk that lasts the configured time
 * means the user stood up, so the sitting countdown restarts: once when the walk qualifies,
 * and once more, silently, when it ends, so the new countdown starts from the last step.
 *
 * Step times come from the sensor event timestamps rather than delivery time. With the screen
 * off many phones deliver steps in batches, and the timestamps still say when each step
 * happened.
 *
 * Shares the countdown notification (TimerCountdownNotifier) as its foreground notification,
 * so a running work phase shows one entry in the shade, not two.
 */
class WalkResetService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val walkEndCheck = Runnable { checkWalkEnded() }

    /** Fallback for phones without a step detector: the cumulative counter, -1 until read. */
    private var lastCounterValue = -1f

    /** Sensor-clock (elapsed realtime) nanos; -1 when no walk is in progress. */
    private var walkStartNanos = -1L
    private var lastStepNanos = -1L

    /** The current walk already restarted the timer. */
    private var walkTriggered = false

    /** Progress last written to the notification, in [PROGRESS_STEP_SECONDS] buckets. */
    private var shownProgressBucket = -1L

    companion object {
        /** Stops shorter than this (a door, a traffic light) do not end a walk. */
        private const val MAX_GAP_NANOS = 15_000_000_000L
        private const val MAX_GAP_MILLIS = 15_000L
        private const val PROGRESS_STEP_SECONDS = 5L
        private val RESET_VIBRATION = longArrayOf(0, 120, 100, 120, 100, 120)

        /** Line the countdown notification shows while this service runs; null when stopped. */
        @Volatile
        var statusLine: String? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TimerManager.init(this)
        val state = TimerManager.state.value
        if (statusLine == null) statusLine = idleLine()
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
            Sensor.TYPE_STEP_DETECTOR -> onStep(event.timestamp)
            Sensor.TYPE_STEP_COUNTER -> {
                val count = event.values.firstOrNull() ?: return
                val previous = lastCounterValue
                lastCounterValue = count
                // The first reading is the total since boot, a baseline rather than a step.
                if (previous >= 0f && count > previous) onStep(event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onStep(stepNanos: Long) {
        if (walkStartNanos < 0 || stepNanos - lastStepNanos > MAX_GAP_NANOS) {
            if (walkTriggered) finishWalk()
            walkStartNanos = stepNanos
        }
        lastStepNanos = maxOf(lastStepNanos, stepNanos)

        val walkedNanos = lastStepNanos - walkStartNanos
        val thresholdMillis = TimerManager.state.value.settings.walkResetMillis
        if (!walkTriggered && walkedNanos >= thresholdMillis * 1_000_000L) {
            walkTriggered = true
            vibrate()
            TimerManager.restartWorkAfterWalk(this, toWallClock(walkStartNanos))
        }
        showProgress(walkedNanos / 1_000_000_000L, thresholdMillis)

        handler.removeCallbacks(walkEndCheck)
        handler.postDelayed(walkEndCheck, MAX_GAP_MILLIS)
    }

    /** Runs a gap after the last step; a late batch of steps may still extend the walk. */
    private fun checkWalkEnded() {
        if (walkStartNanos < 0) return
        val sinceLastStepNanos = SystemClock.elapsedRealtimeNanos() - lastStepNanos
        if (sinceLastStepNanos < MAX_GAP_NANOS) {
            handler.postDelayed(walkEndCheck, (MAX_GAP_NANOS - sinceLastStepNanos) / 1_000_000L + 1)
            return
        }
        if (walkTriggered) finishWalk()
        walkStartNanos = -1L
        walkTriggered = false
        setStatus(idleLine())
    }

    /** The walk that restarted the timer is over: count sitting time from its last step. */
    private fun finishWalk() {
        walkTriggered = false
        TimerManager.reanchorWorkAfterWalk(this, toWallClock(lastStepNanos))
    }

    private fun showProgress(walkedSeconds: Long, thresholdMillis: Long) {
        val bucket = if (walkTriggered) Long.MAX_VALUE else walkedSeconds / PROGRESS_STEP_SECONDS
        if (bucket == shownProgressBucket) return
        shownProgressBucket = bucket
        setStatus(
            if (walkTriggered) {
                "Ходьба: отсчёт начнётся с последнего шага"
            } else {
                "Ходьба ${walkedSeconds} сек из ${formatDurationShort(thresholdMillis)}"
            }
        )
    }

    private fun idleLine(): String {
        shownProgressBucket = -1L
        return "Идёт отсчёт · педометр следит"
    }

    private fun setStatus(line: String) {
        statusLine = line
        TimerCountdownNotifier.sync(this, TimerManager.state.value)
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
        // Removing the foreground notification takes the countdown with it; post it again if
        // the timer is still paused or running without this service.
        stopForeground(STOP_FOREGROUND_REMOVE)
        TimerCountdownNotifier.sync(this, TimerManager.state.value)
        super.onDestroy()
    }
}
