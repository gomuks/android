package app.gomuks.android

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
data class PushData(
    @SerialName("dismiss") val dismiss: List<PushDismiss>? = null,
    @SerialName("messages") val messages: List<PushMessage>? = null,
    @SerialName("image_auth") val imageAuth: String? = null,
    @SerialName("image_auth_expiry") val imageAuthExpiry: Long? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
data class PushDismiss(
    @SerialName("room_id") val roomID: String,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
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
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
data class PushUser(
    val id: String,
    val name: String,
    val avatar: String? = null,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
data class SentMessage(
    val event: String,
    val room: PushUser,
    @SerialName("dm_user") val dmUser: PushUser? = null,
    @SerialName("image_auth") val imageAuth: String? = null,
)
