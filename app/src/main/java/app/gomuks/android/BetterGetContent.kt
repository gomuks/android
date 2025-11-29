package app.gomuks.android

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.gomuks.android.BetterGetContent.Result
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class BetterGetContent(private val activity: ComponentActivity) : ActivityResultContract<BetterGetContent.Params, Result?>() {
    companion object {
        private const val LOGTAG = "Gomuks/BetterGetContent"
    }

    class Params(
        val mimeTypes: Array<String>? = null,
        val multiple: Boolean = false,
    ) {
        val type: String
            get() {
                if (mimeTypes == null) {
                    return "*/*"
                }
                var mimeType: String? = null
                var mimeSubtype: String? = null
                for (rawType in mimeTypes) {
                    val normalizedType = rawType.trim { it <= ' ' }.lowercase()
                    val len = normalizedType.length
                    var slash = normalizedType.indexOf('/')
                    if (slash < 0) {
                        slash = len
                    }
                    val newType = normalizedType.substring(0, slash)
                    val newSubtype: String =
                        normalizedType.substring(min(slash + 1, len))
                    if (mimeType == null) {
                        mimeType = newType
                    } else if (mimeType != newType) {
                        mimeType = "*"
                    }
                    if (mimeSubtype == null) {
                        mimeSubtype = newSubtype
                    } else if (mimeSubtype != newSubtype) {
                        mimeSubtype = "*"
                    }
                }
                return (mimeType ?: "*") + '/' + (mimeSubtype ?: "*")
            }
    }

    class Result(
        val uri: Uri?,
        val clip: ClipData?,
    )
    private var currentPhotoTempFile: File? = null
    private var currentVideoTempFile: File? = null

    private fun getPhotoTempURI(context: Context): Uri {
        val tempFile = currentPhotoTempFile ?: run {
            val file = File(context.cacheDir, "camera_output.jpg")
            currentPhotoTempFile = file
            file.createNewFile()
            file
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
    }

    private fun getVideoTempURI(context: Context): Uri {
        val tempFile = currentVideoTempFile ?: run {
            val file = File(context.cacheDir, "camera_output.mp4")
            currentVideoTempFile = file
            file.createNewFile()
            file
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
    }

    override fun createIntent(context: Context, input: Params): Intent {
        Log.d(LOGTAG, "Creating intent with type=${input.type}, multiple=${input.multiple}, ${input.mimeTypes?.size} mime types")
        val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.type
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            if (!input.mimeTypes.isNullOrEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, input.mimeTypes)
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, input.multiple)
        }
        val imageURI = getPhotoTempURI(context)
        val videoURI = getVideoTempURI(context)
        val takePicture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, imageURI)
        }
        val takeVideo = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, videoURI)
        }
        val pm = context.packageManager
        pm.queryIntentActivities(takePicture, 0).forEach {
            Log.d(LOGTAG, "Found camera activity ${it.activityInfo.packageName}.${it.activityInfo.name}")
            context.grantUriPermission(it.activityInfo.packageName, imageURI, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        pm.queryIntentActivities(takeVideo, 0).forEach {
            Log.d(LOGTAG, "Found video activity ${it.activityInfo.packageName}.${it.activityInfo.name}")
            context.grantUriPermission(it.activityInfo.packageName, videoURI, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        pm.queryIntentActivities(getContent, 0).forEach {
            Log.d(LOGTAG, "Found content activity ${it.activityInfo.packageName}.${it.activityInfo.name}")
        }

        return Intent.createChooser(getContent, "Select source").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(getContent, takePicture, takeVideo))
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Result? {
        if (resultCode != Activity.RESULT_OK) {
            Log.d(LOGTAG, "Non-OK result $resultCode for file picking")
            return null
        } else if (intent != null && (intent.data != null || intent.clipData != null)) {
            Log.d(LOGTAG, "Using result URI ${intent.data} / ${intent.clipData?.itemCount}")
            return Result(
                uri = intent.data,
                clip = intent.clipData,
            )
        }

        val photoFile = currentPhotoTempFile ?: File(activity.cacheDir, "camera_output.jpg")
        val videoFile = currentVideoTempFile ?: File(activity.cacheDir, "camera_output.mp4")
        if (intent == null && photoFile.exists() && videoFile.exists()) {
            val currentTS = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            currentPhotoTempFile = null
            currentVideoTempFile = null
            val destFile: File
            if (photoFile.length() != 0L) {
                destFile = File(File(photoFile.parentFile, "upload"), "Camera_$currentTS.jpg")
                photoFile.renameTo(destFile)
                videoFile.delete()
            } else if (videoFile.length() != 0L) {
                destFile = File(File(videoFile.parentFile, "upload"), "Camera_$currentTS.mp4")
                videoFile.renameTo(destFile)
                photoFile.delete()
            } else {
                Log.w(LOGTAG, "No result found, temp files are empty")
                return null
            }
            Log.d(LOGTAG, "Using photo/video URI ${destFile.path}")
            return Result(uri = destFile.toUri(), clip = null)
        }
        Log.w(LOGTAG, "No temp files or result found")
        return null
    }
}