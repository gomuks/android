package app.gomuks.android

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

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
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val avatars = NotificationAvatars(this, getServerURL(this), data.imageAuth, data.imageAuthExpiry)
        data.messages?.forEach {
            showMessageNotification(it, avatars)
        }
    }

    private fun pushUserToPerson(data: PushUser, avatars: NotificationAvatars): Person {
        return Person.Builder()
            .setKey(data.id)
            .setName(data.name)
            .setUri("matrix:u/${data.id.substring(1)}")
            .setIcon(avatars.load(data.avatar)?.let { IconCompat.createWithBitmap(it) })
            .build()
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showMessageNotification(data: PushMessage, avatars: NotificationAvatars) {
        val sender = pushUserToPerson(data.sender, avatars)
        val roomAvatar = avatars.load(data.roomAvatar)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notifID = data.roomID.hashCode()
        val messagingStyle = (manager.activeNotifications.lastOrNull { it.tag == null && it.id == notifID }?.let {
            MessagingStyle.extractMessagingStyleFromNotification(it.notification)
        } ?: MessagingStyle(pushUserToPerson(data.self, avatars)))
            .setConversationTitle(if (!data.isDM) data.roomName else null)
            .setGroupConversation(!data.isDM)
            .addMessage(MessagingStyle.Message(data.text, data.timestamp, sender))
        val channelID = if (data.sound) {
            NOISY_NOTIFICATION_CHANNEL_ID
        } else {
            SILENT_NOTIFICATION_CHANNEL_ID
        }

        val openRoomIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                setAction(Intent.ACTION_VIEW)
                setData("matrix:roomid/${data.roomID.substring(1)}/e/${data.eventID.substring(1)}".toUri())
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_reply,
            getString(R.string.reply),
            PendingIntent.getBroadcast(
                this,
                notifID,
                Intent(this, ReplyReceiver::class.java).apply {
                    setAction(ReplyReceiver.INTENT_ACTION)
                    setData("matrix:roomid/${data.roomID.substring(1)}/e/${data.eventID.substring(1)}?action=reply".toUri())
                    putExtra(ReplyReceiver.KEY_ROOM_ID, data.roomID)
                    putExtra(ReplyReceiver.KEY_ROOM_NAME, data.roomName)
                },
                PendingIntent.FLAG_MUTABLE,
            ),
        ).addRemoteInput(
            RemoteInput.Builder(ReplyReceiver.KEY_REPLY)
                .setLabel(resources.getString(R.string.reply))
                .build(),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()

        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_mark_read,
            getString(R.string.mark_read),
            PendingIntent.getBroadcast(
                this,
                notifID,
                Intent(this, MarkReadReceiver::class.java).apply {
                    setAction(MarkReadReceiver.INTENT_ACTION)
                    setData("matrix:roomid/${data.roomID.substring(1)}/e/${data.eventID.substring(1)}?action=mark_read".toUri())
                    putExtra(MarkReadReceiver.KEY_ROOM_ID, data.roomID)
                    putExtra(MarkReadReceiver.KEY_EVENT_ID, data.eventID)
                },
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build()

        val builder = NotificationCompat.Builder(this, channelID)
            .setSmallIcon(R.drawable.matrix)
            .setLargeIcon(roomAvatar)
            .setStyle(messagingStyle)
            .setWhen(data.timestamp)
            .setAutoCancel(true)
            .setContentIntent(openRoomIntent)
            .addAction(replyAction)
            .addAction(markReadAction)
        try {
            val shortcut = ShortcutInfoCompat.Builder(this, data.roomID)
                .setShortLabel(data.roomName.ifBlank { data.roomID })
                .setIsConversation()
                .setPerson(sender)
                .setIntent(Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    setData("matrix:roomid/${data.roomID.substring(1)}".toUri())
                })
                .setIcon(if (roomAvatar != null) {
                    IconCompat.createWithBitmap(roomAvatar)
                } else if (data.isDM && sender.icon != null) {
                    sender.icon
                } else {
                    null
                })
                .build()
            if (ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)) {
                builder.setShortcutInfo(shortcut)
            }
        } catch (e: Exception) {
            Log.w(LOGTAG, "Failed to publish conversation shortcut", e)
        }
        NotificationManagerCompat.from(this).notify(notifID.hashCode(), builder.build())
    }
}
