package app.gomuks.android

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import androidx.core.net.toUri

class GeckoPrompts(private val activity: ComponentActivity) : BasicGeckoViewPrompt(activity) {
    companion object {
        private const val LOGTAG = "Gomuks/GeckoPrompts"
    }

    private var currentFileResponse: GeckoResult<PromptResponse>? = null
    private var currentFilePrompt: FilePrompt? = null

    private val filePrompt = activity.registerForActivityResult(BetterGetContent()) {
        onFileCallbackResult(it)
    }

    private fun Uri.toFileUri(): Uri {
        if (this.scheme == "file") {
            return this
        }
        val uri = this
        val temporalFile = File(File(activity.cacheDir, "upload"), uri.getFileName())
        try {
            val inStream = activity.contentResolver.openInputStream(uri) as FileInputStream
            val outStream = FileOutputStream(temporalFile)
            val inChannel = inStream.channel
            val outChannel = outStream.channel
            inChannel.transferTo(0, inChannel.size(), outChannel)
            inStream.close()
            outStream.close()
        } catch (e: IOException) {
            Log.w(LOGTAG, "Could not convert uri to file uri", e)
        }
        return ("file:///" + temporalFile.absolutePath).toUri()
    }

    private fun Uri.getFileName(): String {
        var fileName = ""
        this.let { returnUri ->
            activity.contentResolver.query(returnUri, null, null, null, null)
        }?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            fileName = cursor.getString(nameIndex)
        }
        return fileName
    }

    private fun onFileCallbackResult(result: BetterGetContent.Result?) {
        val res = currentFileResponse ?: return
        val prompt = currentFilePrompt ?: return
        currentFileResponse = null
        currentFilePrompt = null

        if (result == null) {
            res.complete(prompt.dismiss())
            return
        }
        if (result.uri != null
            && (prompt.type == FilePrompt.Type.SINGLE
                || (prompt.type == FilePrompt.Type.MULTIPLE && result.clip == null))) {
            val fileURI = result.uri.toFileUri()
            Log.d(LOGTAG, "onFileCallbackResult -> ${result.uri} -> $fileURI")
            res.complete(prompt.confirm(activity, fileURI))
        } else if (result.clip != null && prompt.type == FilePrompt.Type.MULTIPLE) {
            Log.d(LOGTAG, "onFileCallbackResult -> multiple items")
            res.complete(prompt.confirm(activity, Array(result.clip.itemCount, { i ->
                val uri = result.clip.getItemAt(i).uri
                val fileURI = uri.toFileUri()
                Log.d(LOGTAG, "onFileCallbackResult -> $i = $uri -> $fileURI")
                fileURI
            })))
        } else {
            Log.w(LOGTAG, "No selected file")
            res.complete(prompt.dismiss())
        }
    }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: FilePrompt
    ): GeckoResult<PromptResponse> {
        Log.d(LOGTAG, "onFilePrompt")
        val res = GeckoResult<PromptResponse>()

        try {
            currentFileResponse = res
            currentFilePrompt = prompt
            filePrompt.launch(
                BetterGetContent.Params(
                    mimeTypes = prompt.mimeTypes,
                    multiple = prompt.type == FilePrompt.Type.MULTIPLE,
                )
            )
        } catch (e: ActivityNotFoundException) {
            Log.e(LOGTAG, "Cannot launch activity", e)
            return GeckoResult.fromValue(prompt.dismiss())
        }

        return res
    }
}