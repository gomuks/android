package app.gomuks.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal fun avatarRequest(serverURL: String?, path: String?, token: String?): Request? {
    if (path?.startsWith("_gomuks/media/") != true || token.isNullOrBlank() || serverURL.isNullOrBlank()) {
        return null
    }
    val url = serverURLBuilder(serverURL).build().resolve(path) ?: return null
    return Request.Builder()
        .url(url.newBuilder().setQueryParameter("thumbnail", "avatar").build())
        .header("Authorization", "Image $token")
        .build()
}

internal class NotificationAvatars(
    context: Context,
    private val serverURL: String?,
    private val token: String?,
    private val expiry: Long?,
) {
    private val client = avatarHTTPClient(context)
    private val cache = mutableMapOf<String, Bitmap?>()
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

    fun load(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        if (cache.containsKey(path)) return cache[path]
        val bitmap = try {
            download(path)
        } catch (e: Exception) {
            Log.w("Gomuks/NotificationAvatars", "Avatar download failed: $e")
            null
        }
        cache[path] = bitmap
        return bitmap
    }

    private fun download(path: String): Bitmap? {
        if (expiry != null && expiry <= System.currentTimeMillis()) {
            return null
        }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) {
            return null
        }
        val call = client.newCall(avatarRequest(serverURL, path, token) ?: return null)
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
        return call.execute().use { response ->
            val maxBytes = 1024 * 1024
            if (
                !response.isSuccessful
                || response.body.contentType()?.subtype == "svg+xml"
                || response.body.contentLength() > maxBytes
            ) {
                return null
            }
            val bytes = response.body.byteStream().readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) {
                return null
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}
