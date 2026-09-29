package app.gomuks.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class MarkReadReceiver : BroadcastReceiver() {
    companion object {
        const val INTENT_ACTION = "app.gomuks.android.MARK_READ"
        const val KEY_ROOM_ID = "room_id"
        const val KEY_EVENT_ID = "event_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != INTENT_ACTION) return
        val roomID = intent.getStringExtra(KEY_ROOM_ID) ?: return
        val eventID = intent.getStringExtra(KEY_EVENT_ID) ?: return

        val work = OneTimeWorkRequestBuilder<MarkReadWorker>()
            .setInputData(workDataOf(
                KEY_ROOM_ID to roomID,
                KEY_EVENT_ID to eventID,
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(work)
        NotificationManagerCompat.from(context).cancel(roomID.hashCode())
    }
}

class MarkReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val LOGTAG = "Gomuks/MarkReadWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val roomID = inputData.getString(MarkReadReceiver.KEY_ROOM_ID)
            ?: return@withContext Result.failure()
        val eventID = inputData.getString(MarkReadReceiver.KEY_EVENT_ID)
            ?: return@withContext Result.failure()
        val credentials = readCredentials(applicationContext) ?: return@withContext Result.failure()
        val (serverURL, username, password) = credentials
        val request = Request.Builder()
            .url(serverURLBuilder(serverURL).addPathSegments("_gomuks/exec/mark_read").build())
            .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
            .post(
                JSONObject()
                    .put("room_id", roomID)
                    .put("event_id", eventID)
                    .put("receipt_type", "m.read") // TODO private read receipts?
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success()
                } else {
                    Log.w(LOGTAG, "Mark read request failed with HTTP ${response.code}")
                    if (response.code == 429 || response.code in 500..599) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(LOGTAG, "Failed to mark room as read", e)
            Result.retry()
        }
    }
}
