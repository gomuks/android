package app.gomuks.android

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

class MessageDelegate(private val activity: MainActivity) : WebExtension.MessageDelegate {
    companion object {
        private const val LOGTAG = "Gomuks/MessageDelegate"
    }

    override fun onConnect(port: WebExtension.Port) {
        port.setDelegate(activity.portDelegate)
        activity.port = port
        Log.d(LOGTAG, "Port connected ${port.name}")
    }
}

class PortDelegate(private val activity: MainActivity) : WebExtension.PortDelegate {
    companion object {
        private const val LOGTAG = "Gomuks/PortDelegate"
    }
    private var lastPushReg: TimeSource.Monotonic.ValueTimeMark? = null

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        if (message is JSONObject) {
            Log.d(LOGTAG, "Received message from web: $message")
            when (val evtType = message.getString("event")) {
                "not_gomuks" -> {
                    Log.i(LOGTAG, "Web page is not gomuks")
                    activity.openServerInputWithError(activity.getString(R.string.not_gomuks_error))
                }

                "ready" -> {
                    Log.i(LOGTAG, "Web client loaded")
                    sendAuthCredentials(port)
                    activity.resetPadding()
                }

                "auth_fail" -> {
                    val failMsg = message.getString("error")
                    Log.i(LOGTAG, "Got auth fail event $failMsg")
                    activity.openServerInputWithError(
                        "${activity.getString(R.string.authentication_failed_error)}: $failMsg"
                    )
                }

                "connected" -> {
                    Log.i(LOGTAG, "Client connected to WebSocket")
                    val now = TimeSource.Monotonic.markNow()
                    val pushToken = activity.sharedPref.getString(
                        activity.getString(R.string.push_token_key),
                        null
                    )
                    if (pushToken != null && now - (lastPushReg ?: (now - 13.hours)) > 12.hours) {
                        registerPush(port, pushToken)
                        lastPushReg = now
                    } else if (pushToken == null) {
                        Log.d(LOGTAG, "Not sending push registration: no token saved")
                    } else {
                        Log.d(LOGTAG, "Not sending push registration: last registration was recent")
                    }
                }

                else -> Log.d(LOGTAG, "Unknown web command $evtType")
            }
        } else {
            Log.d(LOGTAG, "Received message from web with unexpected type: $message")
        }
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(LOGTAG, "Port disconnected ${port.name}")
        if (port == activity.port) {
            activity.port = null
        }
    }

    fun sendAuthCredentials(port: WebExtension.Port) {
        val creds = activity.getCredentials()
        if (creds != null) {
            val (_, username, password) = creds
            val basicAuth = Base64.encodeToString(
                "$username:$password".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            port.postMessage(
                JSONObject(
                    mapOf(
                        "type" to "auth",
                        "authorization" to "Basic $basicAuth",
                    )
                )
            )
            Log.i(LOGTAG, "Sent auth credentials")
        } else {
            Log.w(LOGTAG, "No credentials found")
            activity.openServerInputWithError(activity.getString(R.string.no_credentials_found_error))
        }
    }

    fun registerPush(port: WebExtension.Port, token: String) {
        port.postMessage(
            JSONObject(
                mapOf(
                    "type" to "register_push",
                    "device_id" to activity.deviceID.toString(),
                    "token" to token,
                    "encryption" to mapOf(
                        "key" to activity.getPushEncryptionKey(),
                    ),
                )
            )
        )
        Log.i(LOGTAG, "Sent push registration with token $token")
    }
}
