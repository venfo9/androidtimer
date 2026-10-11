package com.workbreaktimer.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything the app knows about Shizuku: whether it can be used right now, and the one call
 * the airplane schedule needs from it.
 */
object ShizukuBridge {

    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 4242
    private const val BINDER_WAIT_MILLIS = 2_000L
    private const val SERVICE_WAIT_SECONDS = 4L

    enum class Status(val label: String) {
        NOT_INSTALLED("Shizuku не установлен"),
        NOT_RUNNING("Shizuku не запущен"),
        OUTDATED("версия Shizuku устарела"),
        NO_PERMISSION("у приложения нет разрешения Shizuku"),
        READY("Shizuku работает")
    }

    private val _status = MutableStateFlow(Status.NOT_RUNNING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var listening = false

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val onBinderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    /** Starts following Shizuku's state so the screen updates when it starts, stops or grants access. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!listening) {
            listening = true
            Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
            Shizuku.addBinderDeadListener(onBinderDead)
            Shizuku.addRequestPermissionResultListener(onPermissionResult)
        }
        refresh()
    }

    fun refresh() {
        appContext?.let { _status.value = computeStatus(it) }
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    fun openIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))

    private fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun computeStatus(context: Context): Status {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return if (isInstalled(context)) Status.NOT_RUNNING else Status.NOT_INSTALLED
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return Status.OUTDATED
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) Status.READY else Status.NO_PERMISSION
    }

    /**
     * Status for code that runs in a freshly started process, such as an alarm receiver.
     * Shizuku hands its binder over a moment after the process starts, so checking at once
     * would report "not running" for a Shizuku that works fine. Blocks; keep off the main thread.
     */
    fun awaitStatus(context: Context): Status {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive && isInstalled(context)) {
            val latch = CountDownLatch(1)
            val listener = Shizuku.OnBinderReceivedListener { latch.countDown() }
            Shizuku.addBinderReceivedListenerSticky(listener, mainHandler)
            latch.await(BINDER_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            Shizuku.removeBinderReceivedListener(listener)
        }
        return computeStatus(context).also { _status.value = it }
    }

    /**
     * Switches airplane mode through AirplaneUserService. Returns null on success, otherwise a
     * reason fit to show the user. Blocks for a few seconds; keep off the main thread.
     */
    fun setAirplaneModeBlocking(context: Context, enabled: Boolean): String? {
        val status = awaitStatus(context)
        if (status != Status.READY) return status.label

        val service = AtomicReference<IAirplaneService?>(null)
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    service.set(IAirplaneService.Stub.asInterface(binder))
                }
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, AirplaneUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("airplane")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        mainHandler.post {
            runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure { connected.countDown() }
        }
        connected.await(SERVICE_WAIT_SECONDS, TimeUnit.SECONDS)

        val bound = service.get()
        val error = if (bound == null) {
            "Shizuku не запустил службу приложения"
        } else {
            try {
                bound.setAirplaneMode(enabled)
            } catch (e: Exception) {
                "служба приложения не ответила: ${e.message}"
            }
        }
        mainHandler.post { runCatching { Shizuku.unbindUserService(args, connection, true) } }
        return error
    }
}
