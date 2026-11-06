package dev.redrain.cast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.screencopy.sender.PlaybackMute
import dev.screencopy.sender.ProjectionOwner
import dev.screencopy.sender.SenderSession
import dev.screencopy.sender.Settings
import dev.screencopy.sender.Wire

class CastService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: SenderSession? = null
    private var projection: ProjectionOwner? = null
    private var requestId: Long? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("cast", "Screen sharing", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            if (intent.getLongExtra("request", -1) == requestId) session?.stop()
            if (session == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY
        if (intent?.action != "start") {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            val request = CastState.requests.adopt(intent.getLongExtra("request", -1))
            requestId = request.id
            val settings = Settings.from(Wire.parseJson(request.settings.toByteArray()))
            @Suppress("DEPRECATION")
            val consent = checkNotNull(intent.getParcelableExtra<Intent>("projection")) { "Screen-sharing consent is required" }
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (settings.audio && settings.audioSource == "mic") ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(1, notification("Starting screen sharing"), type)
            val grant = checkNotNull(getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, consent))
            projection = ProjectionOwner(grant, resources.configuration.densityDpi, PlaybackMute.create(this)) { error ->
                session?.captureFailed(error)
            }
            val current = SenderSession(applicationContext, settings, request.target, ::status) {
                main.post { finishSession() }
            }
            current.projectionOwner = checkNotNull(projection)
            session = current
            Thread(current, "RedrainCast-sender").start()
        } catch (error: Exception) {
            status("Could not send: ${error.message}")
            android.util.Log.e("RedrainCast", "Sender startup failed", error)
            finishSession()
        }
        return START_NOT_STICKY
    }

    private fun status(message: String) {
        CastState.message = message
        if (message.startsWith("Error:") || message.startsWith("Could not send:")) CastState.failure = message
        main.post {
            if (session != null && (android.os.Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
                getSystemService(NotificationManager::class.java).notify(1, notification(message))
        }
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, CastService::class.java).setAction("stop")
            .setData(android.net.Uri.parse("redraincast://stop/$requestId")).putExtra("request", requestId ?: -1),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "cast")
            .setSmallIcon(android.R.drawable.ic_menu_upload).setContentTitle("RedrainCast")
            .setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    private fun finishSession() {
        session = null
        try { projection?.close() } catch (error: Exception) {
            CastState.message = "Projection/audio cleanup failed: ${error.message}"
            CastState.failure = CastState.message
            android.util.Log.e("RedrainCast", CastState.message, error)
        }
        projection = null
        requestId?.let { CastState.requests.finish(it) }
        requestId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        session?.stop()
        try { projection?.close() } catch (error: Exception) {
            CastState.message = "Projection/audio shutdown failed: ${error.message}"
            CastState.failure = CastState.message
            android.util.Log.e("RedrainCast", CastState.message, error)
        }
        projection = null
        if (session == null) requestId?.let { CastState.requests.finish(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
