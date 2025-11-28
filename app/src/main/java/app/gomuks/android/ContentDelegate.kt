package app.gomuks.android

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebResponse
import java.util.LinkedList
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
        if (response.body == null) return

        var mime: String? = sanitizeMimeType(response.headers["Content-Type"])
        if (mime == null || mime.isEmpty()) {
            mime = "*/*"
        }
        val filename = getFileName(response, mime)
        val contentValues = ContentValues()
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        Log.i(LOGTAG, "Downloading $filename of type $mime")
        contentValues.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS
        )
        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1)

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val fileUri = activity.contentResolver.insert(collection, contentValues)
        if (fileUri == null) {
            Toast.makeText(activity, "Unable to access downloads directory", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, "Downloading $filename", Toast.LENGTH_LONG).show()
        val bufferSize = 1024 // to read in 1Mb increments
        val buffer = ByteArray(bufferSize)
        try {
            activity.contentResolver.openOutputStream(fileUri).use { out ->
                var len: Int
                while ((response.body!!.read(buffer).also { len = it }) != -1) {
                    out!!.write(buffer, 0, len)
                }
            }
        } catch (e: Throwable) {
            Log.e(LOGTAG, "Failed to download: ${e.stackTrace}")
        }
        contentValues.clear()
        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
        activity.contentResolver.update(fileUri, contentValues, null, null)
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