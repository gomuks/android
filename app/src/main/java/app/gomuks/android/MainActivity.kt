package app.gomuks.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.WindowManager
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
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val navigation = NavigationDelegate(this)
    private val messageDelegate = MessageDelegate(this)
    internal val portDelegate = PortDelegate(this)

    private lateinit var view: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var runtime: GeckoRuntime

    internal lateinit var sharedPref: SharedPreferences
    private lateinit var prefEnc: Encryption
    internal lateinit var deviceID: UUID

    internal var port: WebExtension.Port? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (this::runtime.isInitialized) {
            parseIntentURL(intent)?.let {
                session.loadUri(it)
            }
        }
    }

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
                Log.d("GomuksMainActivity", "Generated new device ID $deviceID")
            } else {
                Log.d("GomuksMainActivity", "Parsing UUID $it")
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
            Log.e("GomuksMainActivity", "Failed to decrypt password", e)
            return null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSharedPref()
        createNotificationChannels(this)
        view = GeckoView(this)
        session = GeckoSession()
        runtime = GeckoRuntime.create(this)
        session.open(runtime)
        view.setSession(session)

        runtime.settings.enterpriseRootsEnabled = true
        runtime.settings.consoleOutputEnabled = true
        runtime.settings.doubleTapZoomingEnabled = false

        session.promptDelegate = BasicGeckoViewPrompt(this)
        session.navigationDelegate = navigation

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBarsInsets.top,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }

        val sessWebExtController = session.webExtensionController
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/bridge/", "android@gomuks.app")
            .accept(
                { extension ->
                    if (extension != null) {
                        Log.i("GomuksMainActivity", "Extension installed: $extension")
                        sessWebExtController.setMessageDelegate(
                            extension,
                            messageDelegate,
                            "gomuksAndroid"
                        )
                    } else {
                        Log.e("GomuksMainActivity", "Installed extension is null?")
                    }
                },
                { e -> Log.e("GomuksMainActivity", "Error registering WebExtension", e) }
            )

        CoroutineScope(Dispatchers.Main).launch {
            tokenFlow.collect { pushToken ->
                Log.i(
                    "GomuksMainActivity",
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
        if (!loadWeb()) {
            setContent {
                ServerInput()
            }
        }
        Log.i("GomuksMainActivity", "Initialization complete")
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

    private fun parseIntentURL(overrideIntent: Intent? = null): String? {
        var serverURL = getServerURL() ?: return null
        val intent = overrideIntent ?: this.intent
        var targetURI = intent.data
        if (intent.action == Intent.ACTION_VIEW && targetURI != null) {
            if (targetURI.host == "matrix.to") {
                targetURI = matrixToURLToMatrixURI(targetURI)
                if (targetURI == null) {
                    Log.w("GomuksMainActivity", "Failed to parse matrix.to URL ${intent.data}")
                } else {
                    Log.d("GomuksMainActivity", "Parsed matrix.to URL ${intent.data} -> $targetURI")
                }
            }
            if (targetURI?.scheme == "matrix") {
                serverURL = Uri.parse(serverURL)
                    .buildUpon()
                    .encodedFragment("/uri/${Uri.encode(targetURI.toString())}")
                    .build()
                    .toString()
                Log.d("GomuksMainActivity", "Converted view intent $targetURI -> $serverURL")
                return serverURL
            }
        }
        if (overrideIntent != null) {
            Log.w(
                "GomuksMainActivity",
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
