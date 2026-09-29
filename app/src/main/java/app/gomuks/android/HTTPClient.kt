package app.gomuks.android

import android.content.Context
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

internal val httpClient = OkHttpClient.Builder()
    .callTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(true)
    .build()

private var avatarClient: OkHttpClient? = null

@Synchronized
internal fun avatarHTTPClient(context: Context): OkHttpClient {
    return avatarClient ?: httpClient.newBuilder()
        .cache(Cache(File(context.applicationContext.cacheDir, "notification-avatars"), 50 * 1024 * 1024))
        .build()
        .also { avatarClient = it }
}

fun serverURLBuilder(serverURL: String): HttpUrl.Builder {
    val baseURL = serverURL.toHttpUrl()
    return baseURL.newBuilder().encodedPath(baseURL.encodedPath.trimEnd('/') + "/")
}
