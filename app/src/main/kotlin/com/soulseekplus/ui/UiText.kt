package com.soulseekplus.ui

import android.content.res.Resources
import com.soulseekplus.R
import com.soulseekplus.core.model.ConnectionState
import com.soulseekplus.core.model.GroupMode
import com.soulseekplus.core.model.PortMappingState
import com.soulseekplus.core.model.SearchScope
import com.soulseekplus.core.model.ShareLevel
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferState
import com.soulseekplus.core.model.UserStatus
import java.text.DateFormat
import java.util.Date

/*
 * Çekirdek katmanı kullanıcıya dönük metin üretmez: sabit İngilizce sebepler ve enum'lar verir. Burada hepsi
 * kaynak dizgelerine eşlenir (values / values-tr). Bilinmeyen sebepler olduğu gibi gösterilir.
 */

private fun Resources.locale(): java.util.Locale = configuration.locales[0]

fun Resources.connectionText(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> state.reason?.takeIf { it.isNotBlank() }?.let { getString(R.string.conn_disconnected_reason, reasonText(it)) }
        ?: getString(R.string.conn_disconnected)
    ConnectionState.Connecting -> getString(R.string.conn_connecting)
    is ConnectionState.LoggedIn -> getString(R.string.conn_connected_as, state.username)
    is ConnectionState.Reconnecting -> getString(R.string.conn_reconnecting, state.attempt)
}

fun Resources.shortConnectionText(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> getString(R.string.conn_disconnected)
    ConnectionState.Connecting -> getString(R.string.conn_connecting)
    is ConnectionState.LoggedIn -> state.username
    is ConnectionState.Reconnecting -> getString(R.string.conn_reconnecting_short)
}

fun Resources.transferStateText(t: Transfer): String = when (t.state) {
    TransferState.QUEUED -> getString(R.string.tstate_queued)
    TransferState.QUEUED_REMOTE ->
        t.queuePosition?.let { getString(R.string.tstate_queued_remote_pos, it) } ?: getString(R.string.tstate_queued_remote)
    TransferState.CONNECTING -> getString(R.string.tstate_connecting)
    TransferState.TRANSFERRING -> getString(R.string.tstate_transferring)
    TransferState.PAUSED -> getString(R.string.tstate_paused)
    TransferState.FINISHED -> getString(R.string.tstate_finished)
    TransferState.CANCELLED -> getString(R.string.tstate_cancelled)
    TransferState.FAILED -> t.failReason?.takeIf { it.isNotBlank() }?.let { reasonText(it) } ?: getString(R.string.tstate_failed)
    TransferState.DENIED -> t.failReason?.takeIf { it.isNotBlank() }?.let { reasonText(it) } ?: getString(R.string.tstate_denied)
    TransferState.USER_OFFLINE -> getString(R.string.tstate_user_offline)
}

/** Protokol ve motor sebeplerini yerelleştirir (bkz. docs/slsk-protocol.md). Tanınmayan sebep aynen döner. */
fun Resources.reasonText(reason: String): String {
    val key = reason.trim().trimEnd('.').lowercase()
    val id = when {
        key == "file not shared" -> R.string.reason_not_shared
        key == "banned" || key.startsWith("banned") -> R.string.reason_banned
        key == "queued" -> R.string.reason_queued
        key == "complete" -> R.string.reason_complete
        key == "cancelled" -> R.string.reason_cancelled
        key == "pending shutdown" -> R.string.reason_pending_shutdown
        key == "too many files" -> R.string.reason_too_many_files
        key == "too many megabytes" || key.startsWith("user limit of") -> R.string.reason_too_many_megabytes
        key == "file read error" -> R.string.reason_file_read_error
        key == "disallowed extension" -> R.string.reason_disallowed_extension
        key == "user offline" || key == "user logged off" -> R.string.reason_user_offline
        key == "connection timeout" || key == "timeout" -> R.string.reason_timeout
        key == "connection closed" || key == "connection closed by peer" -> R.string.reason_connection_closed
        key == "wrong password" || key == "invalid password" || key == "invalid username" -> R.string.reason_bad_credentials
        else -> 0
    }
    return if (id != 0) getString(id) else reason
}

fun Resources.userStatusText(status: UserStatus): String = when (status) {
    UserStatus.ONLINE -> getString(R.string.status_online)
    UserStatus.AWAY -> getString(R.string.status_away)
    UserStatus.OFFLINE -> getString(R.string.status_offline)
}

fun Resources.scopeText(scope: SearchScope): String = when (scope) {
    SearchScope.Network -> getString(R.string.scope_network)
    is SearchScope.Room -> getString(R.string.scope_room_named, scope.room)
    SearchScope.Buddies -> getString(R.string.scope_buddies)
    is SearchScope.User -> getString(R.string.scope_user_named, scope.username)
    SearchScope.Wishlist -> getString(R.string.scope_wishlist)
}

fun Resources.groupModeText(mode: GroupMode): String = when (mode) {
    GroupMode.FOLDER -> getString(R.string.group_folder)
    GroupMode.USER -> getString(R.string.group_user)
    GroupMode.FLAT -> getString(R.string.group_flat)
}

fun Resources.levelText(level: ShareLevel, group: String? = null): String = when (level) {
    ShareLevel.PUBLIC -> getString(R.string.level_public)
    ShareLevel.BUDDIES -> getString(R.string.level_buddies)
    ShareLevel.GROUP -> if (group.isNullOrBlank()) getString(R.string.level_group) else getString(R.string.level_group_named, group)
}

fun Resources.portMappingText(state: PortMappingState): String = when (state) {
    PortMappingState.Disabled -> getString(R.string.port_disabled)
    PortMappingState.Discovering -> getString(R.string.port_discovering)
    is PortMappingState.Mapped -> getString(R.string.port_mapped, state.method, state.externalPort)
    is PortMappingState.Failed -> getString(R.string.port_failed)
}

fun Resources.dateTimeText(millis: Long): String =
    if (millis <= 0L) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale()).format(Date(millis))

fun Resources.timeText(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, locale()).format(Date(millis))

fun Resources.dayText(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale()).format(Date(millis))

/** "5 minutes ago" gibi kaba göreli zaman. */
fun Resources.agoText(thenMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> getString(R.string.ago_now)
        seconds < 3600 -> getString(R.string.ago_minutes, (seconds / 60).toInt())
        seconds < 86_400 -> getString(R.string.ago_hours, (seconds / 3600).toInt())
        else -> getString(R.string.ago_days, (seconds / 86_400).toInt())
    }
}

/** Tek dosya tablosundaki "kalite" hücresi. */
fun qualityText(extension: String, bitrate: Int?, sampleRate: Int?, bitDepth: Int?, vbr: Boolean?): String =
    com.soulseekplus.core.format.Formatting.quality(extension, bitrate, sampleRate, bitDepth, vbr)
