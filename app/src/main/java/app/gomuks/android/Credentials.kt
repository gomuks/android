package app.gomuks.android

import android.content.Context
import android.util.Log

internal fun readCredentials(context: Context): Triple<String, String, String>? {
    val prefs = context.getSharedPreferences(
        context.getString(R.string.preference_file_key), Context.MODE_PRIVATE,
    )
    val serverURL = prefs.getString(context.getString(R.string.server_url_key), null) ?: return null
    val username = prefs.getString(context.getString(R.string.username_key), null) ?: return null
    val encryptedPassword = prefs.getString(context.getString(R.string.password_key), null) ?: return null
    return try {
        val encryption = Encryption(context.getString(R.string.pref_enc_key_name))
        Triple(serverURL, username, encryption.decrypt(encryptedPassword))
    } catch (e: Exception) {
        Log.e("Gomuks/Credentials", "Failed to decrypt password", e)
        null
    }
}
