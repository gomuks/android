package app.gomuks.android

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

internal const val SILENT_NOTIFICATION_CHANNEL_ID = "silent_notification"
internal const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_notification"
internal const val NOISY_NOTIFICATION_CHANNEL_ID = "noisy_notification"

fun createNotificationChannels(context: Context) {
    val notificationManager = NotificationManagerCompat.from(context)
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            SILENT_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(context.getString(R.string.notification_channel_silent))
            .setDescription(context.getString(R.string.notification_channel_silent))
            .setSound(null, null)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            NOISY_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(context.getString(R.string.notification_channel_noisy))
            .setDescription(context.getString(R.string.notification_channel_noisy))
            .setVibrationEnabled(true)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            DOWNLOAD_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(context.getString(R.string.notification_channel_download))
            .setDescription(context.getString(R.string.notification_channel_download))
            .setSound(null, null)
            .setLightsEnabled(false)
            .build()
    )
}