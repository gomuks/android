package app.gomuks.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.io.File
import java.util.UUID


class MainActivity : ComponentActivity() {
    companion object {
        private const val LOGTAG = "Gomuks/MainActivity"
        private const val BUNDLE_KEY = "gecko"
        private var runtime: GeckoRuntime? = null

        private fun getRuntime(activity: MainActivity): GeckoRuntime {
            return runtime ?: run {
                val rt = GeckoRuntime.create(activity)
                rt.settings.enterpriseRootsEnabled = true
                rt.settings.consoleOutputEnabled = true
                rt.settings.doubleTapZoomingEnabled = false
                runtime = rt
                rt
            }
        }
    }
    private val navigation = NavigationDelegate(this)
    private val messageDelegate = MessageDelegate(this)
    internal val portDelegate = PortDelegate(this)
    private val promptDelegate = GeckoPrompts(this)

    private lateinit var view: GeckoView
    private lateinit var session: GeckoSession
    private var sessionState: GeckoSession.SessionState? = null

    internal lateinit var sharedPref: SharedPreferences
    private lateinit var prefEnc: Encryption
    internal lateinit var deviceID: UUID

    internal var port: WebExtension.Port? = null

    private fun initSharedPref() {
        sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        prefEnc = Encryption(getString(R.string.pref_enc_key_name))
        sharedPref.getString(getString(R.string.device_id_key), null).let {
            if (it == null) {
                deviceID = UUID.randomUUID()
                with(sharedPref.edit()) {
                    putString(getString(R.string.device_id_key), deviceID.toString())
                    apply()
                }
                Log.d(LOGTAG, "Generated new device ID $deviceID")
            } else {
                Log.d(LOGTAG, "Parsing UUID $it")
                deviceID = UUID.fromString(it)
            }
        }
    }

    internal fun getPushEncryptionKey(): String {
        return Base64.encodeToString(getOrCreatePushEncryptionKey(this, prefEnc, sharedPref), Base64.NO_WRAP)
    }

    private fun setCredentials(serverURL: String, username: String, password: String) {
        with(sharedPref.edit()) {
            putString(getString(R.string.server_url_key), serverURL)
            putString(getString(R.string.username_key), username)
            putString(getString(R.string.password_key), prefEnc.encrypt(password))
            apply()
        }
    }

    internal fun getCredentials(): Triple<String, String, String>? {
        val serverURL = sharedPref.getString(getString(R.string.server_url_key), null)
        val username = sharedPref.getString(getString(R.string.username_key), null)
        val encPassword = sharedPref.getString(getString(R.string.password_key), null)
        if (serverURL == null || username == null || encPassword == null) {
            return null
        }
        try {
            return Triple(serverURL, username, prefEnc.decrypt(encPassword))
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to decrypt password", e)
            return null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSharedPref()
        createNotificationChannels(this)
        view = GeckoView(this)
        session = GeckoSession()
        val runtime = getRuntime(this)
        session.open(runtime)
        view.setSession(session)
        addSystemInsets()

        File(cacheDir, "upload").mkdirs()

        session.progressDelegate = object : ProgressDelegate {
            override fun onSessionStateChange(
                session: GeckoSession,
                newState: GeckoSession.SessionState
            ) {
                super.onSessionStateChange(session, newState)
                Log.d(LOGTAG, "onSessionStateChange $newState")
                sessionState = newState
            }
        }
        session.promptDelegate = promptDelegate
        session.navigationDelegate = navigation

        val sessWebExtController = session.webExtensionController
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/bridge/", "android@gomuks.app")
            .accept(
                { extension ->
                    if (extension != null) {
                        Log.i(LOGTAG, "Extension installed: $extension")
                        sessWebExtController.setMessageDelegate(
                            extension,
                            messageDelegate,
                            "gomuksAndroid"
                        )
                    } else {
                        Log.e(LOGTAG, "Installed extension is null?")
                    }
                },
                { e -> Log.e(LOGTAG, "Error registering WebExtension", e) }
            )

        CoroutineScope(Dispatchers.Main).launch {
            tokenFlow.collect { pushToken ->
                Log.i(
                    LOGTAG,
                    "Received push token from messaging service: $pushToken"
                )
                portDelegate.registerPush(port ?: return@collect, pushToken)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigation.canGoBack) {
                    session.goBack()
                    return
                }
                finish()
            }
        })
        val parcel = savedInstanceState?.getParcelable(BUNDLE_KEY, GeckoSession.SessionState::class.java)
        if (parcel != null) {
            session.restoreState(parcel)
            setContentView(view)
        } else if (!loadWeb()) {
            setContent {
                ServerInput()
            }
        }
        Log.i(LOGTAG, "Initialization complete (loaded saved state: ${parcel != null})")
    }

    override fun onStart() {
        super.onStart()
        Log.i(LOGTAG, "onStart")
        session.setActive(true)
    }

    override fun onPause() {
        super.onPause()
        Log.i(LOGTAG, "onPause")
    }

    override fun onResume() {
        super.onResume()
        Log.i(LOGTAG, "onResume")
    }

    override fun onStop() {
        super.onStop()
        Log.i(LOGTAG, "onStop")
        session.setActive(false)
    }

    override fun onRestart() {
        super.onRestart()
        Log.i(LOGTAG, "onRestart")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(LOGTAG, "onDestroy")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        Log.d(LOGTAG, "onSaveInstanceState $sessionState")
        outState.putParcelable(BUNDLE_KEY, sessionState)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (this::session.isInitialized) {
            parseIntentURL(intent)?.let {
                session.loadUri(it)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        val windowInsets = ViewCompat.getRootWindowInsets(window.decorView)
        val statusBarInset = windowInsets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val offsetEvent = MotionEvent.obtain(ev)

        offsetEvent.offsetLocation(0f, -statusBarInset.toFloat())
        return super.dispatchTouchEvent(offsetEvent)
    }

    fun getServerURL(): String? {
        return sharedPref.getString(getString(R.string.server_url_key), null)
    }

    fun openServerInputWithError(error: String) {
        val (serverURL, username, password) = getCredentials() ?: Triple("", "", "")
        setContent {
            ServerInput(serverURL, username, password, error)
        }
    }

    private fun addSystemInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            if (imeInsets.bottom > 0) {
                v.setPadding(0, systemInsets.top, 0, imeInsets.bottom)
            } else {
                v.setPadding(0, systemInsets.top, 0, 0)
            }

            insets
        }
    }

    private fun parseIntentURL(overrideIntent: Intent? = null): String? {
        var serverURL = getServerURL() ?: return null
        val intent = overrideIntent ?: this.intent
        var targetURI = intent.data
        if (intent.action == Intent.ACTION_VIEW && targetURI != null) {
            if (targetURI.host == "matrix.to") {
                targetURI = matrixToURLToMatrixURI(targetURI)
                if (targetURI == null) {
                    Log.w(LOGTAG, "Failed to parse matrix.to URL ${intent.data}")
                } else {
                    Log.d(LOGTAG, "Parsed matrix.to URL ${intent.data} -> $targetURI")
                }
            }
            if (targetURI?.scheme == "matrix") {
                serverURL = Uri.parse(serverURL)
                    .buildUpon()
                    .encodedFragment("/uri/${Uri.encode(targetURI.toString())}")
                    .build()
                    .toString()
                Log.d(LOGTAG, "Converted view intent $targetURI -> $serverURL")
                return serverURL
            }
        }
        if (overrideIntent != null) {
            Log.w(
                LOGTAG,
                "No intent URL found ${overrideIntent.action} ${overrideIntent.data}"
            )
            return null
        }
        return serverURL
    }

    private fun loadWeb(): Boolean {
        session.loadUri(parseIntentURL() ?: return false)
        setContentView(view)
        return true
    }

    @Composable
    fun ServerInput(
        initialURL: String = "",
        initialUsername: String = "",
        initialPassword: String = "",
        error: String? = null
    ) {
        var serverURL by remember { mutableStateOf(initialURL) }
        var username by remember { mutableStateOf(initialUsername) }
        var password by remember { mutableStateOf(initialPassword) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TextField(
                value = serverURL,
                onValueChange = { serverURL = it },
                label = { Text(getString(R.string.server_url)) }
            )
            TextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(getString(R.string.username)) }
            )
            TextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(getString(R.string.password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Button(onClick = {
                setCredentials(serverURL, username, password)
                loadWeb()
            }) {
                Text(getString(R.string.connect))
            }
            if (error != null) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
