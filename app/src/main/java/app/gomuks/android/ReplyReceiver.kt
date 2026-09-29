package app.gomuks.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

class ReplyReceiver : BroadcastReceiver() {
    companion object {
        const val INTENT_ACTION = "app.gomuks.android.REPLY"
        const val KEY_REPLY = "key_reply"
        const val KEY_ROOM_ID = "room_id"
        const val KEY_START_TS = "start_ts"
        const val KEY_TXN_ID = "txn_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != INTENT_ACTION) return

        val roomID = intent.getStringExtra(KEY_ROOM_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)
            ?.toString()
            ?.takeIf { it.isNotBlank() } ?: return

        val work = OneTimeWorkRequestBuilder<ReplyWorker>()
            .setInputData(workDataOf(
                KEY_ROOM_ID to roomID,
                KEY_REPLY to text,
                KEY_TXN_ID to UUID.randomUUID().toString(),
                KEY_START_TS to System.currentTimeMillis().toString(),
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(work)
    }
}

class ReplyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val LOGTAG = "Gomuks/ReplyWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val roomID = inputData.getString(ReplyReceiver.KEY_ROOM_ID)
            ?: return@withContext Result.failure()
        val text = inputData.getString(ReplyReceiver.KEY_REPLY)?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure()
        val txnID = inputData.getString(ReplyReceiver.KEY_TXN_ID)
            ?: return@withContext Result.failure()
        val startTS = inputData.getString(ReplyReceiver.KEY_START_TS)
            ?: return@withContext Result.failure()

        val sent = try {
            sendMessage(roomID, text, txnID, startTS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to send reply", e)
            false
        }

        updateNotification(roomID, text, sent)
        if (sent) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    private fun sendMessage(roomID: String, text: String, txnID: String, startTS: String): Boolean {
        val credentials = readCredentials(applicationContext) ?: return false
        val (serverURL, username, password) = credentials
        val baseURL = serverURL.toHttpUrl()
        val reqURL = baseURL.newBuilder()
            .encodedPath(baseURL.encodedPath.trimEnd('/') + "/")
            .addPathSegments("_gomuks/exec/send_message")
            .addQueryParameter("txn_id", txnID)
            .addQueryParameter("start_ts", startTS)
            .build()
        val reqBody =  JSONObject()
            .put("room_id", roomID)
            .put("text", text)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(reqURL)
            .post(reqBody)
            .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(LOGTAG, "Reply request failed with HTTP ${response.code}")
            }
            response.isSuccessful
        }
    }

    private fun updateNotification(roomID: String, text: String, sent: Boolean) {
        val context = applicationContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            val active = manager.activeNotifications.lastOrNull { it.id == roomID.hashCode() }
                ?: return
            val notification = Notification.Builder.recoverBuilder(context, active.notification)
                .setRemoteInputHistory(arrayOf(text))
                .setSubText(context.getString(if (sent) R.string.reply_sent else R.string.reply_failed))
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(active.tag, active.id, notification)
        } catch (e: Exception) {
            Log.w(LOGTAG, "Failed to update reply notification", e)
        }
    }
}
