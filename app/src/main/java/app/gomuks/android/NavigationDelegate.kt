package app.gomuks.android

import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import androidx.core.net.toUri
import org.mozilla.geckoview.AllowOrDeny

class NavigationDelegate(private val activity: MainActivity) : GeckoSession.NavigationDelegate {
    companion object {
        private const val LOGTAG = "Gomuks/NavigationDelegate"
    }

    var canGoBack = false

    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
        this.canGoBack = canGoBack
    }

    override fun onLoadError(
        session: GeckoSession,
        uri: String?,
        error: WebRequestError
    ): GeckoResult<String>? {
        Log.e(LOGTAG, "onLoadError: $uri $error (${errorToString(error.category)}: ${errorToString(error.code)}")
        val serverURL = activity.getServerURL()
        if (uri == null || (serverURL != null && uri.trimEnd('/') == serverURL.trimEnd('/'))) {
            activity.openServerInputWithError("${activity.getString(R.string.server_load_error)}: ${errorToString(error.code)}")
        }
        return null
    }

    override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
        val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
        if (activity.packageManager.resolveActivity(intent, PackageManager.MATCH_ALL) != null) {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.url_app_chooser_title)))
            Log.d(LOGTAG, "onNewSession: $uri - activity chooser started")
        } else {
            Log.w(LOGTAG, "onNewSession: $uri - activity not found")
        }
        return null
    }
}