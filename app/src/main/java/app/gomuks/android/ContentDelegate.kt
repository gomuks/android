package app.gomuks.android

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse
import java.util.regex.Matcher
import java.util.regex.Pattern


class ContentDelegate(private val activity: MainActivity) : GeckoSession.ContentDelegate {
    companion object {
        private const val LOGTAG = "Gomuks/ContentDelegate"
    }

    override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
        Log.d(LOGTAG, "Got external response ${response.uri}")
        downloadFile(response)
    }

    private fun sanitizeMimeType(mimeType: String?): String? {
        return if (mimeType != null) {
            if (mimeType.contains(";")) {
                mimeType.split(";".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()[0].trim { it <= ' ' }
            } else {
                mimeType.trim { it <= ' ' }
            }
        } else {
            null
        }
    }

    private fun downloadFile(response: WebResponse) {
        val body = response.body ?: return
        val headers = response.headers

        var mime: String? = sanitizeMimeType(headers["Content-Type"])
        if (mime.isNullOrEmpty()) {
            mime = "*/*"
        }
        val filename = getFileName(response, mime)
        Toast.makeText(activity, "Downloading $filename", Toast.LENGTH_LONG).show()

        CoroutineScope(Dispatchers.IO).launch {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            Log.i(LOGTAG, "Downloading $filename of type $mime")

            val resolver = activity.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val fileUri = resolver.insert(collection, contentValues)

            if (fileUri == null) {
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(activity, "Unable to access downloads directory", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val notificationManager = NotificationManagerCompat.from(activity)
            val notificationId = System.currentTimeMillis().toInt()

            val builder = NotificationCompat.Builder(activity, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Downloading $filename")
                .setSmallIcon(R.drawable.ic_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .setOnlyAlertOnce(true)

            fun notify() {
                if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(notificationId, builder.build())
                }
            }

            notify()

            val buffer = ByteArray(8192)
            val contentLength = headers["Content-Length"]?.toLongOrNull()
                ?: headers["content-length"]?.toLongOrNull() ?: -1L
            var totalRead = 0L
            var lastUpdate = 0L

            try {
                resolver.openOutputStream(fileUri)?.use { out ->
                    var len: Int
                    while (body.read(buffer).also { len = it } != -1) {
                        out.write(buffer, 0, len)
                        totalRead += len

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            if (contentLength > 0L) {
                                val progress = ((totalRead.toDouble() / contentLength.toDouble()) * 100).toInt()
                                builder.setProgress(100, progress, false)
                                builder.setSubText("$progress%")
                            }
                            notify()
                            lastUpdate = now
                        }
                    }
                } ?: throw Exception("Failed to open output stream")

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(fileUri, contentValues, null, null)

                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, mime)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                val pendingIntent = PendingIntent.getActivity(
                    activity,
                    notificationId,
                    openIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                builder.setContentTitle("Downloaded $filename")
                    .setSubText(null)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)

                notify()

            } catch (e: Exception) {
                Log.e(LOGTAG, "Failed to download", e)
                builder.setContentTitle("Failed to download $filename")
                    .setContentText(e.toString())
                    .setOngoing(false)
                    .setProgress(0, 0, false)
                notify()
            }
        }
    }

    private val filenamePattern: Pattern = Pattern.compile("(filename=\"?)(.+)(\"?)")

    private fun getFileName(response: WebResponse, mime: String): String {
        var filename: String? = null
        val contentDispositionHeader = if (response.headers.containsKey("content-disposition")) {
            response.headers["content-disposition"]
        } else {
            response.headers.getOrDefault("Content-Disposition", "default filename=GomuksDownload")
        }
        val matcher: Matcher = filenamePattern.matcher(contentDispositionHeader ?: "")
        if (matcher.find()) {
            filename = matcher.group(2)
                ?.replace("\\s".toRegex(), "%20")
                ?.replace("\"".toRegex(), "")
        }
        if (filename == null) {
            val rawMime = mime.split(';').first()
            filename = if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
                rawMime.replace("/", ".")
            } else if (mime.startsWith("application/")) {
                "file.dat"
            } else {
                "file.${rawMime.split("/").last()}"
            }
        }

        return filename
    }
}
