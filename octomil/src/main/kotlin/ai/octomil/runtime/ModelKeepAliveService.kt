package ai.octomil.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that raises OOM adj priority while an LLM is loaded.
 *
 * Without this, the low memory killer reclaims the app when the user opens
 * the image picker or camera — forcing a full model reload on return.
 *
 * SDK clients get this service auto-merged into their manifest. Usage:
 * ```
 * ModelKeepAliveService.start(context, "smolvlm2-500m")
 * // ... later, when leaving chat:
 * ModelKeepAliveService.stop(context)
 * ```
 */
class ModelKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelName = intent?.getStringExtra(EXTRA_MODEL_NAME) ?: "model"
        startForeground(NOTIFICATION_ID, buildNotification(modelName))
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the LLM loaded while the app is in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(modelName: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Model active")
            .setContentText(modelName)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "octomil_model_runtime"
        private const val NOTIFICATION_ID = 9001
        private const val EXTRA_MODEL_NAME = "model_name"

        fun start(context: Context, modelName: String) {
            val intent = Intent(context, ModelKeepAliveService::class.java)
                .putExtra(EXTRA_MODEL_NAME, modelName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ModelKeepAliveService::class.java))
        }
    }
}
