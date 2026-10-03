package app.gomuks.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.Log
import okhttp3.Request
import okhttp3.CacheControl
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat

internal fun avatarRequest(serverURL: String?, path: String?, token: String?, cached: Boolean): Request? {
    if (path?.startsWith("_gomuks/media/") != true || (token.isNullOrBlank() && !cached) || serverURL.isNullOrBlank()) {
        return null
    }
    val url = serverURLBuilder(serverURL).build().resolve(path) ?: return null
    val builder = Request.Builder()
        .url(url.newBuilder()
            .query(null)
            .setQueryParameter("encrypted", "false")
            .setQueryParameter("fallback", url.queryParameter("fallback"))
            .setQueryParameter("thumbnail", "avatar")
            .build())
    if (cached) {
        return builder
            .cacheControl(CacheControl.FORCE_CACHE)
            .build()
    } else {
        return builder
            .header("Authorization", "Image $token")
            .build()
    }
}

internal class NotificationAvatars(
    context: Context,
    private val serverURL: String?,
    private val token: String?,
    private val expiry: Long?,
) {
    companion object {
        private const val LOGTAG = "Gomuks/NotificationAvatars"
    }
    private val client = avatarHTTPClient(context)
    private val backgroundColor =
        if (context.resources.configuration.isNightModeActive) Color.BLACK else Color.WHITE
    private val cache = mutableMapOf<String, IconCompat?>()
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

    fun load(path: String?): IconCompat? {
        if (path.isNullOrBlank()) return null
        if (cache.containsKey(path)) return cache[path]
        val icon = try {
            val downloaded = download(path, true) ?: download(path, false)
            downloaded?.let { IconCompat.createWithAdaptiveBitmap(padForAdaptiveIcon(it)) }
        } catch (e: Exception) {
            Log.w(LOGTAG, "Avatar download failed: $e")
            null
        }
        cache[path] = icon
        return icon
    }

    private fun padForAdaptiveIcon(bitmap: Bitmap): Bitmap {
        val scale = 1f + 2f * AdaptiveIconDrawable.getExtraInsetFraction()
        val padded = createBitmap(
            ceil(bitmap.width * scale).toInt(),
            ceil(bitmap.height * scale).toInt(),
        )
        padded.density = bitmap.density
        Canvas(padded).apply {
            drawColor(backgroundColor)
            drawBitmap(
                bitmap,
                (padded.width - bitmap.width) / 2f,
                (padded.height - bitmap.height) / 2f,
                null,
            )
        }
        return padded
    }

    private fun download(path: String, cached: Boolean): Bitmap? {
        if (expiry != null && expiry <= System.currentTimeMillis() && !cached) {
            return null
        }
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0 && !cached) {
            return null
        }
        val request = avatarRequest(serverURL, path, token, cached) ?: return null
        val call = client.newCall(request)
        if (!cached) {
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            Log.d(LOGTAG, "Downloading ${request.url} from network (${remaining / 1_000_000} ms remaining)")
        }
        return call.execute().use { response ->
            val maxBytes = 1024 * 1024
            if (
                !response.isSuccessful
                || response.body.contentType()?.subtype == "svg+xml"
                || response.body.contentLength() > maxBytes
            ) {
                if (!cached) {
                    Log.d(LOGTAG, "Response for ${request.url} failed (status=${response.code}, size=${response.body.contentLength()}, type=${response.body.contentType()})")
                }
                return null
            }
            val bytes = response.body.byteStream().readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) {
                Log.d(LOGTAG, "Response for ${request.url} (cached: $cached) was too big while reading")
                return null
            }
            if (cached) {
                Log.d(LOGTAG, "Downloaded ${request.url} from cache (${bytes.size} bytes)")
            } else {
                Log.d(LOGTAG, "Downloaded ${request.url} from network (${bytes.size} bytes)")
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}
