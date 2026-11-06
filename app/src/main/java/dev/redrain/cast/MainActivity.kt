package dev.redrain.cast

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.screencopy.sender.Discovery
import dev.screencopy.sender.PlaybackMute
import dev.screencopy.sender.ProjectionInventory
import dev.screencopy.sender.ReceiverTarget
import dev.screencopy.sender.Settings
import org.screencopy.receiver.lifecycle.ResourceSlot
import org.screencopy.receiver.media.VideoPlayer
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.ReceiverOrientation
import org.screencopy.receiver.ui.ReceiverScreenEvent
import org.screencopy.receiver.ui.SessionOrientation
import org.screencopy.receiver.ui.VideoPresentation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : Activity(), TextureView.SurfaceTextureListener {
    private class ListenerRun(val surface: Surface) {
        val output = ResourceSlot<VideoPlayer>()
        @Volatile var server: ReceiverServer? = null
        @Volatile var port: Int? = null
    }
    internal val currentListeningPort: Int? get() = listenerRun?.port
    internal val resourcesRetired: Boolean get() = worker.isTerminated

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private lateinit var home: HomeView
    private lateinit var videoBox: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var settings: Settings
    private var discovery: Discovery? = null
    private var discoveryAttempted = false
    private var receivers = emptyList<Discovery.Receiver>()
    private val self = SelfReceivers(CastState.instanceId)
    @Volatile private var listenerRun: ListenerRun? = null
    @Volatile private var foreground = false
    private var decoderSurface: Surface? = null
    private var listenerFault = false
    private var readiness = "STARTING"
    private var detail = ""
    private var endpoints = emptyList<String>()
    private var problem: String? = null
    private var playing = false
    private var aspect = 16.0 / 9
    private var rotation = 0
    private var hasPresentation = false
    private var pendingRequestId: Long? = null
    private var networkPermissionRequested = false
    private var networkPermissionDenied = false
    private var muteRecoveryPending = true
    private val sessionOrientation = SessionOrientation({ requestedOrientation }, { requestedOrientation = it })
    private var dialog: AlertDialog? = null
    private var settingsDialog: AlertDialog? = null
    private val refresh = object : Runnable {
        override fun run() {
            reconcile()
            main.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        networkPermissionRequested = savedInstanceState?.getBoolean("networkPermissionRequested") ?: false
        networkPermissionDenied = savedInstanceState?.getBoolean("networkPermissionDenied") ?: false
        if (CastState.roles.role() != RoleOwner.Role.SEND) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        savedInstanceState?.takeIf { it.containsKey("previousOrientation") }?.let {
            requestedOrientation = it.getInt("previousOrientation")
        }
        settings = SharingSettings.load(this, ::showError)
        val pending = CastState.requests.snapshot()
        if (savedInstanceState?.getLong("pendingRequest", -1) == pending?.id && pending?.adopted == false) {
            pendingRequestId = pending.id
            if (!pending.consentStarted) cancelPending(getString(R.string.flow_send_cancelled))
        }
        root = FrameLayout(this)
        videoBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this)
        videoBox.addView(texture, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        texture.surfaceTextureListener = this
        videoBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        root.addView(videoBox, FrameLayout.LayoutParams(-1, -1))
        home = HomeView(this, ::showSettings, ::manualConnect, ::prepareSend, ::stopSending, ::showHelp) {
            problem = null
            CastState.failure = null
            render()
        }
        home.onLocalNetworkPermission = ::requestLocalNetworkPermission
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showError(error: Exception) {
        android.util.Log.e("RedrainCast", "Operation failed", error)
        problem = error.message ?: error.javaClass.simpleName
        if (::home.isInitialized) render()
    }

    private fun render() {
        if (!::home.isInitialized || isDestroyed) return
        val request = CastState.requests.snapshot()
        val role = CastState.roles.role()
        home.render(HomeView.State(
            readiness = if (role == RoleOwner.Role.SEND) "PAUSED" else readiness,
            detail = if (role == RoleOwner.Role.SEND) getString(R.string.flow_receive_paused) else detail,
            endpoints = if (role == RoleOwner.Role.SEND) emptyList() else endpoints,
            sending = role == RoleOwner.Role.SEND,
            receiving = role == RoleOwner.Role.RECEIVE,
            outgoing = request?.target, status = CastState.message,
            error = problem ?: CastState.failure, settingsSummary = SharingSettings.summary(settings),
            localNetworkPermissionRequired = !LocalNetworkPermission.granted(this)))
        home.setReceivers(self.visible(receivers))
    }

    private fun reconcile() {
        if (!foreground || isDestroyed) return
        if (!LocalNetworkPermission.granted(this)) {
            if (listenerRun != null) retireListener()
            closeDiscovery()
            readiness = "PERMISSION REQUIRED"
            detail = getString(R.string.flow_network_permission_detail)
            endpoints = emptyList()
            render()
            if (!networkPermissionRequested) requestLocalNetworkPermission()
            return
        }
        if (CastState.roles.role() != RoleOwner.Role.SEND && decoderSurface?.isValid == true &&
            listenerRun == null && !listenerFault) startListener()
        if (discovery == null && !discoveryAttempted) startDiscovery()
        render()
    }

    private fun requestLocalNetworkPermission() {
        if (!foreground || isDestroyed) return
        if (LocalNetworkPermission.granted(this)) { reconcile(); return }
        if (networkPermissionDenied && !shouldShowRequestPermissionRationale(LocalNetworkPermission.NAME)) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
            return
        }
        networkPermissionRequested = true
        requestPermissions(arrayOf(LocalNetworkPermission.NAME), LocalNetworkPermission.REQUEST)
    }

    private fun closeDiscovery() {
        try { discovery?.close() } catch (error: Exception) { showError(error) }
        discovery = null
        receivers = emptyList()
    }

    private fun startDiscovery() {
        discoveryAttempted = true
        try {
            discovery = Discovery(this, { found -> main.post {
                if (foreground && !isDestroyed) { receivers = found; render() }
            } }, { message -> main.post {
                if (foreground && !isDestroyed) showError(IllegalStateException(message))
            } })
        } catch (error: Exception) {
            showError(IllegalStateException(getString(R.string.flow_discovery_failed, error.message), error))
        }
    }

    private fun startListener() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val surface = decoderSurface ?: return
        val run = ListenerRun(surface)
        listenerRun = run
        readiness = "STARTING"
        detail = getString(R.string.flow_starting)
        endpoints = emptyList()
        worker.execute {
            if (!current(run)) return@execute
            try {
                run.server = ReceiverServer("RedrainCast ${android.os.Build.MODEL}".take(80), run.surface,
                    onStatus = { state, message -> main.post {
                        if (current(run)) {
                            if (state.contains("failed", true) || state.contains("error", true) ||
                                state.contains("timed out", true) || state.contains("stalled", true))
                                showError(IllegalStateException("$state: $message"))
                        }
                    } },
                    onVideoSize = { width, height -> main.post {
                        if (current(run)) {
                            aspect = width.toDouble() / height
                            rotation = 0
                            hasPresentation = false
                            fitVideo()
                        }
                    } },
                    onListener = {}, port = 0, videoOutput = run.output,
                    onScreenEvent = { event -> main.post { if (current(run)) screenEvent(event) } },
                    onPresentationStart = { mode -> beginPresentation(mode, run) },
                    onVideoRotation = { degrees, owns -> main.post {
                        if (current(run) && owns()) {
                            rotation = degrees; hasPresentation = true; fitVideo()
                        }
                    } },
                    acquireSession = {
                        if (!current(run)) null else CastState.roles.tryAcquire(RoleOwner.Role.RECEIVE)?.let { lease ->
                            AutoCloseable {
                                CastState.roles.release(lease)
                                main.post { if (current(run)) { hidePlayer(); render() } }
                            }
                        }
                    },
                    instanceId = CastState.instanceId,
                    onFramePresented = { owns -> main.post {
                        if (current(run) && owns()) {
                            hasPresentation = true
                            fitVideo()
                            showPlayer()
                        }
                    } })
                checkNotNull(run.server).start()
                run.port = checkNotNull(run.server).listeningPort
                self.remember(checkNotNull(run.server).addresses(), checkNotNull(run.server).listeningPort)
            } catch (error: Exception) {
                main.post {
                    if (listenerRun === run) {
                        listenerFault = true
                        readiness = "UNAVAILABLE"
                        detail = getString(R.string.flow_listener_failed)
                        showError(error)
                        retireListener()
                    }
                }
            }
        }
    }

    private fun current(run: ListenerRun) =
        foreground && listenerRun === run && !run.output.isClosed

    private fun screenEvent(event: ReceiverScreenEvent) {
        when (event) {
            is ReceiverScreenEvent.Listener -> {
                endpoints = event.addresses
                val run = listenerRun
                run?.server?.let { self.remember(it.addresses(), it.listeningPort) }
                readiness = if (event.addresses.isEmpty()) "NO NETWORK" else "READY"
                detail = getString(if (event.addresses.isEmpty()) R.string.flow_no_network else R.string.flow_ready)
            }
            ReceiverScreenEvent.Connecting -> {
                problem = null
                CastState.failure = null
                readiness = "CONNECTING"
                detail = getString(R.string.flow_connecting)
                dialog?.dismiss(); dialog = null
                settingsDialog?.dismiss(); settingsDialog = null
            }
            ReceiverScreenEvent.CleanupFinished -> {
                hidePlayer()
                readiness = if (endpoints.isEmpty()) "NO NETWORK" else "READY"
                detail = getString(if (endpoints.isEmpty()) R.string.flow_no_network else R.string.flow_ready)
            }
            is ReceiverScreenEvent.Problem -> {
                showError(IllegalStateException(getString(R.string.flow_receive_problem, event.code)))
                hidePlayer()
            }
            ReceiverScreenEvent.ListenerStopped -> {
                listenerFault = true
                readiness = "UNAVAILABLE"
                detail = getString(R.string.flow_listener_failed)
                retireListener()
            }
            else -> Unit
        }
        render()
    }

    private fun retireListener(after: (Boolean) -> Unit = {}) {
        val run = listenerRun
        listenerRun = null
        endpoints = emptyList()
        hidePlayer()
        worker.execute {
            var failure: Exception? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Exception) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
            cleanup { run?.server?.close() }
            cleanup { run?.output?.close() }
            main.post {
                if (!isDestroyed) {
                    failure?.let { listenerFault = true; showError(it) }
                    after(failure == null)
                }
            }
        }
    }

    private fun prepareSend(target: ReceiverTarget) {
        if (!LocalNetworkPermission.granted(this)) {
            requestLocalNetworkPermission()
            return
        }
        try {
            check(foreground && CastState.roles.role() == null) { getString(R.string.flow_busy) }
            check(!self.contains(target)) { getString(R.string.flow_self_target) }
            val resolved = ProjectionInventory.validate(this, settings, true)
            val request = CastState.requests.reserve(resolved.request().toString(), target)
            pendingRequestId = request.id
            CastState.message = getString(R.string.flow_waiting_consent)
            CastState.failure = null
            problem = null
            render()
            retireListener { success ->
                if (pendingRequestId != request.id) return@retireListener
                if (!success || !foreground) cancelPending(getString(R.string.flow_send_cancelled))
                else try {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        CastState.requests.beginConsent(request.id)
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
                    } else requestAudioOrProjection()
                } catch (error: Exception) { cancelPending(error.message.orEmpty()); showError(error) }
            }
        } catch (error: Exception) { showError(error) }
    }

    private fun pendingRequest(): SendRequests.Request? {
        val request = CastState.requests.snapshot()
        return request?.takeIf { it.id == pendingRequestId && !it.adopted }
    }

    private fun requestAudioOrProjection() {
        val request = pendingRequest() ?: return
        val selected = Settings.from(dev.screencopy.sender.Wire.parseJson(request.settings.toByteArray()))
        if (selected.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            CastState.requests.beginConsent(request.id)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        } else requestProjection()
    }

    private fun requestProjection() {
        val request = pendingRequest() ?: return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val consent = if (android.os.Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        CastState.requests.beginConsent(request.id)
        @Suppress("DEPRECATION")
        startActivityForResult(consent, 11)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == LocalNetworkPermission.REQUEST) {
            networkPermissionDenied = !LocalNetworkPermission.granted(this)
            if (networkPermissionDenied) showError(IllegalStateException(getString(R.string.flow_network_permission_denied)))
            else {
                problem = null
                listenerFault = false
                discoveryAttempted = false
                readiness = "STARTING"
                detail = getString(R.string.flow_starting)
            }
            reconcile()
            return
        }
        if (pendingRequest() == null) return
        try {
            when (code) {
                12 -> {
                    if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED)
                        showError(IllegalStateException(getString(R.string.flow_notifications_denied)))
                    requestAudioOrProjection()
                }
                10 -> if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestProjection()
                    else cancelPending(getString(R.string.flow_audio_denied))
            }
        } catch (error: Exception) { cancelPending(error.message.orEmpty()); showError(error) }
    }

    @Deprecated("Android Activity consent callback")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != 11) return
        val request = pendingRequest() ?: return
        if (result != RESULT_OK || data == null) {
            cancelPending(getString(R.string.flow_capture_denied))
            return
        }
        try {
            startForegroundService(Intent(this, CastService::class.java).setAction("start")
                .putExtra("request", request.id).putExtra("projection", data))
            pendingRequestId = null
        } catch (error: Exception) { cancelPending(error.message.orEmpty()); showError(error) }
    }

    private fun cancelPending(reason: String) {
        val id = pendingRequestId ?: return
        val cancelled = CastState.requests.cancel(id)
        pendingRequestId = null
        if (cancelled) CastState.message = reason
        if (foreground) reconcile()
    }

    private fun stopSending() {
        val request = CastState.requests.snapshot() ?: return
        if (!request.adopted) {
            CastState.requests.cancel(request.id)
            pendingRequestId = null
            CastState.message = getString(R.string.flow_send_cancelled)
            reconcile()
        }
        else try {
            startService(Intent(this, CastService::class.java).setAction("stop").putExtra("request", request.id))
        } catch (error: Exception) { showError(error) }
    }

    private fun manualConnect() {
        if (CastState.roles.role() != null) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0)
        }
        val host = EditText(this).apply {
            setHint(R.string.flow_host); isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val port = EditText(this).apply {
            setHint(R.string.flow_port); isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val error = TextView(this).apply { visibility = View.GONE }
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        content.addView(error); content.addView(host); content.addView(port)
        val prompt = AlertDialog.Builder(this).setTitle(R.string.flow_manual_title).setView(content)
            .setPositiveButton(R.string.flow_send, null).setNegativeButton(android.R.string.cancel, null).create()
        prompt.setOnShowListener {
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val target = ReceiverTarget(host.text.toString().trim(), port.text.toString().toInt())
                    check(!self.contains(target)) { getString(R.string.flow_self_target) }
                    check(CastState.roles.role() == null) { getString(R.string.flow_busy) }
                    prompt.dismiss()
                    prepareSend(target)
                } catch (failure: Exception) {
                    android.util.Log.w("RedrainCast", "Invalid manual endpoint", failure)
                    error.text = failure.message
                    error.visibility = View.VISIBLE
                }
            }
        }
        dialog = prompt
        prompt.show()
    }

    private fun showSettings() {
        if (CastState.roles.role() != null) return
        try {
            settingsDialog = SharingSettings.show(this, settings, { saved -> settings = saved; render() }, ::showNotices)
        } catch (error: Exception) { showError(error) }
    }

    private fun showHelp() {
        dialog = AlertDialog.Builder(this).setTitle(R.string.flow_help_title).setMessage(R.string.flow_help)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showNotices() {
        try {
            val text = buildString {
                append(assets.open("NOTICE.txt").bufferedReader().use { it.readText() })
                assets.list("licenses").orEmpty().sorted().forEach { name ->
                    append("\n\n--- $name ---\n")
                    append(assets.open("licenses/$name").bufferedReader().use { it.readText() })
                }
            }
            dialog = AlertDialog.Builder(this).setTitle(R.string.flow_notices).setMessage(text)
                .setPositiveButton(android.R.string.ok, null).show()
        } catch (error: Exception) { showError(error) }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        decoderSurface = Surface(surface)
        reconcile()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val retired = decoderSurface
        decoderSurface = null
        retireListener()
        worker.execute { try { retired?.release() } finally { surface.release() } }
        return false
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun beginPresentation(mode: ReceiverOrientation, run: ListenerRun): AutoCloseable {
        val token = Any()
        val cancelled = AtomicBoolean()
        val done = CountDownLatch(1)
        val failure = AtomicReference<Exception?>()
        main.post {
            try {
                check(!cancelled.get() && current(run) && !isDestroyed)
                hasPresentation = false
                sessionOrientation.begin(token, mode)
            } catch (error: Exception) { failure.set(error) }
            finally { done.countDown() }
        }
        try {
            check(done.await(3, TimeUnit.SECONDS)) { "Receiver presentation startup timed out" }
            failure.get()?.let { throw it }
        } catch (error: Exception) {
            cancelled.set(true)
            main.post { restoreOrientation(token) }
            throw error
        }
        return AutoCloseable { main.post { restoreOrientation(token) } }
    }

    private fun restoreOrientation(token: Any? = null) {
        try {
            if (token == null) sessionOrientation.restore() else sessionOrientation.restore(token)
        } catch (error: Exception) { showError(error) }
    }

    private fun fitVideo() {
        if (!::texture.isInitialized) return
        VideoPresentation.fit(texture, videoBox, aspect, rotation)
        if (hasPresentation) sessionOrientation.followVideo(aspect, rotation)
    }

    private fun showPlayer() {
        if (playing) return
        playing = true
        home.visibility = View.GONE
        window.insetsController?.hide(WindowInsets.Type.systemBars())
        window.insetsController?.systemBarsBehavior =
            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun hidePlayer() {
        playing = false
        hasPresentation = false
        if (::home.isInitialized) home.visibility = View.VISIBLE
        restoreOrientation()
        window.insetsController?.show(WindowInsets.Type.systemBars())
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        fitVideo()
    }

    override fun onResume() {
        super.onResume()
        if (muteRecoveryPending) {
            muteRecoveryPending = false
            try { PlaybackMute.create(this).restore() } catch (error: Exception) { showError(error) }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (CastState.roles.role() == RoleOwner.Role.SEND) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        listenerFault = false
        readiness = "STARTING"
        detail = getString(R.string.flow_starting)
        discoveryAttempted = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.post(refresh)
    }

    override fun onStop() {
        foreground = false
        main.removeCallbacks(refresh)
        dialog?.dismiss(); dialog = null
        settingsDialog?.dismiss(); settingsDialog = null
        closeDiscovery()
        retireListener()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("networkPermissionRequested", networkPermissionRequested)
        state.putBoolean("networkPermissionDenied", networkPermissionDenied)
        pendingRequestId?.let { state.putLong("pendingRequest", it) }
        sessionOrientation.previousPolicy?.let { state.putInt("previousOrientation", it) }
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        if (isFinishing || pendingRequest()?.consentStarted != true)
            cancelPending(getString(R.string.flow_send_cancelled))
        texture.surfaceTextureListener = null
        val surface = decoderSurface
        decoderSurface = null
        val surfaceTexture = texture.surfaceTexture
        worker.execute { try { surface?.release() } finally { surfaceTexture?.release() } }
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
