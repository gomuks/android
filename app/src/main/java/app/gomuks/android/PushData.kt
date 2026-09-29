package app.gomuks.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PushData(
    @SerialName("dismiss") val dismiss: List<PushDismiss>? = null,
    @SerialName("messages") val messages: List<PushMessage>? = null,
    @SerialName("image_auth") val imageAuth: String? = null,
    @SerialName("image_auth_expiry") val imageAuthExpiry: Long? = null,
)

@Serializable
data class PushDismiss(
    @SerialName("room_id") val roomID: String,
)

@Serializable
data class PushMessage(
    val timestamp: Long,
    @SerialName("event_id") val eventID: String,
    @SerialName("event_rowid") val eventRowID: Long,

    @SerialName("room_id") val roomID: String,
    @SerialName("room_name") val roomName: String,
    @SerialName("room_avatar") val roomAvatar: String? = null,
    val sender: PushUser,
    val self: PushUser,

    @SerialName("is_dm") val isDM: Boolean = false,

    val text: String,
    val image: String? = null,
    val mention: Boolean = false,
    val reply: Boolean = false,
    val sound: Boolean = false,
)

@Serializable
data class PushUser(
    val id: String,
    val name: String,
    val avatar: String? = null,
)
