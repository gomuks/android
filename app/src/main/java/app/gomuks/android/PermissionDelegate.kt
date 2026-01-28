package app.gomuks.android

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaCallback
import java.util.Locale
import androidx.core.net.toUri


class PermissionDelegate(private val activity: MainActivity) : GeckoSession.PermissionDelegate {
    companion object {
        private const val LOGTAG = "Gomuks/PermissionDelegate"
        const val PERMISSION_REQUEST_CODE: Int = 1
    }
    private var mCallback: GeckoSession.PermissionDelegate.Callback? = null

    fun onRequestPermissionsResult(permissions: Array<out String?>, grantResults: IntArray) {
        val cb = mCallback ?: return
        mCallback = null
        for (result in grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                // At least one permission was not granted.
                cb.reject()
                return
            }
        }
        cb.grant()
    }

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    ) {
        mCallback = callback
        if (permissions != null) {
            ActivityCompat.requestPermissions(activity, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    private fun normalizeMediaName(sources: Array<GeckoSession.PermissionDelegate.MediaSource>?): Array<String>? {
        return sources?.map { source ->
            val mediaSource = source.source
            val name = source.name
            if (GeckoSession.PermissionDelegate.MediaSource.SOURCE_CAMERA == mediaSource) {
                if (name!!.lowercase(Locale.ROOT).contains("front")) {
                    activity.getString(R.string.media_front_camera)
                } else {
                    activity.getString(R.string.media_back_camera)
                }
            } else if (name != null && name.isNotEmpty()) {
                name
            } else if (GeckoSession.PermissionDelegate.MediaSource.SOURCE_MICROPHONE == mediaSource) {
                activity.getString(R.string.media_microphone)
            } else {
                activity.getString(R.string.media_other)
            }
        }?.toTypedArray()
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: MediaCallback
    ) {
        // If we don't have device permissions at this point, just automatically reject the request
        // as we will have already have requested device permissions before getting to this point
        // and if we've reached here and we don't have permissions then that means that the user
        // denied them.
        if (
            (audio != null && (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED))
            || (video != null && (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED))
        ) {
            callback.reject()
            return
        }
        Log.i(LOGTAG, "$uri requested media permissions")

        val serverURL = activity.getServerURL()
        if (serverURL != null && uri.startsWith(serverURL)) {
            Log.d(LOGTAG, "Auto-accepting media permissions ${video?.firstOrNull()?.id} ${audio?.firstOrNull()?.id}")
            callback.grant(video?.firstOrNull(), audio?.firstOrNull())
            return
        }

        val host: String? = uri.toUri().authority
        val title: String?
        if (audio == null) {
            title = activity.getString(R.string.request_video, host)
        } else if (video == null) {
            title = activity.getString(R.string.request_audio, host)
        } else {
            title = activity.getString(R.string.request_media, host)
        }

        val videoNames = normalizeMediaName(video)
        val audioNames = normalizeMediaName(audio)

        activity.promptDelegate.onMediaPrompt(
            session, title, video, audio, videoNames, audioNames, callback
        )
    }
}
