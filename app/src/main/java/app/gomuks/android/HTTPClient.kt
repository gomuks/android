package app.gomuks.android

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal val httpClient = OkHttpClient.Builder()
    .callTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(true)
    .build()
