package app.gomuks.android

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.net.toUri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import androidx.core.content.edit

class MessagingService : FirebaseMessagingService() {
    companion object {
        private const val LOGTAG = "Gomuks/MessagingService"
    }

    override fun onNewToken(token: String) {
        val sharedPref =
            getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        sharedPref.edit {
            putString(getString(R.string.push_token_key), token)
        }
        Log.d(LOGTAG, "Got new push token: $token")
        CoroutineScope(Dispatchers.IO).launch {
            tokenFlow.emit(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val pushEncKey = getExistingPushEncryptionKey(this)
        if (pushEncKey == null) {
            Log.e(LOGTAG, "No push encryption key found to handle $message")
            return
        }
        val decryptedPayload: String = try {
            Encryption.fromPlainKey(pushEncKey).decrypt(message.data.getValue("payload"))
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to decrypt $message", e)
            return
        }
        val data = try {
            Json.decodeFromString<PushData>(decryptedPayload)
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to parse $decryptedPayload as JSON", e)
            return
        }
        Log.i(LOGTAG, "Decrypted payload: $data")
        if (!data.dismiss.isNullOrEmpty()) {
            with(NotificationManagerCompat.from(this)) {
                for (dismiss in data.dismiss) {
                    cancel(dismiss.roomID.hashCode())
                }
            }
        }
        data.messages?.forEach {
            showMessageNotification(it)
        }
    }

    private fun pushUserToPerson(data: PushUser): Person {
        // TODO include avatar
        return Person.Builder()
            .setKey(data.id)
            .setName(data.name)
            .setUri("matrix:u/${data.id.substring(1)}")
            .build()
    }

    private fun showMessageNotification(data: PushMessage) {
        val sender = pushUserToPerson(data.sender)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifID = data.roomID.hashCode()
        val messagingStyle = (manager.activeNotifications.lastOrNull { it.id == notifID }?.let {
            MessagingStyle.extractMessagingStyleFromNotification(it.notification)
        } ?: MessagingStyle(pushUserToPerson(data.self)))
            .setConversationTitle(
                if (data.roomName != data.sender.name) data.roomName else null
            )
            .addMessage(MessagingStyle.Message(data.text, data.timestamp, sender))
        val channelID = if (data.sound) {
            NOISY_NOTIFICATION_CHANNEL_ID
        } else {
            SILENT_NOTIFICATION_CHANNEL_ID
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                setAction(Intent.ACTION_VIEW)
                setData("matrix:roomid/${data.roomID.substring(1)}/e/${data.eventID.substring(1)}".toUri())
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, channelID)
            .setSmallIcon(R.drawable.matrix)
            .setStyle(messagingStyle)
            .setWhen(data.timestamp)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(
                    this@MessagingService,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notify(notifID.hashCode(), builder.build())
        }
    }
}
