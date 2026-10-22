package com.soulseekplus.data.transfers

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.soulseekplus.core.model.FileAttributes
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferDirection
import com.soulseekplus.core.model.TransferState

/**
 * [Transfer] satırı. Sıralama/durum alanları metin olarak saklanır (enum sırası değişse de veri bozulmaz);
 * [FileAttributes] düzleştirilmiştir.
 */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val username: String,
    val virtualPath: String,
    val size: Long,
    val bytesTransferred: Long,
    val state: String,
    val failReason: String?,
    val queuePosition: Int?,
    val speedBytesPerSecond: Long,
    val bitrate: Int?,
    val durationSeconds: Int?,
    val vbr: Boolean?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val destinationSubfolder: String?,
    val localUri: String?,
    val addedAtMillis: Long,
    val startedAtMillis: Long?,
    val finishedAtMillis: Long?,
    val retryCount: Int,
) {
    /** Bilinmeyen enum değeri içeren (ör. sürüm düşürme) satırlar için null. */
    fun toTransfer(): Transfer? {
        val dir = enumOrNull<TransferDirection>(direction) ?: return null
        val st = enumOrNull<TransferState>(state) ?: return null
        return Transfer(
            id = id,
            direction = dir,
            username = username,
            virtualPath = virtualPath,
            size = size,
            bytesTransferred = bytesTransferred,
            state = st,
            failReason = failReason,
            queuePosition = queuePosition,
            speedBytesPerSecond = speedBytesPerSecond,
            attributes = FileAttributes(bitrate, durationSeconds, vbr, sampleRate, bitDepth),
            destinationSubfolder = destinationSubfolder,
            localUri = localUri,
            addedAtMillis = addedAtMillis,
            startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
            retryCount = retryCount,
        )
    }

    companion object {
        fun from(t: Transfer): TransferEntity = TransferEntity(
            id = t.id,
            direction = t.direction.name,
            username = t.username,
            virtualPath = t.virtualPath,
            size = t.size,
            bytesTransferred = t.bytesTransferred,
            state = t.state.name,
            failReason = t.failReason,
            queuePosition = t.queuePosition,
            speedBytesPerSecond = t.speedBytesPerSecond,
            bitrate = t.attributes.bitrate,
            durationSeconds = t.attributes.durationSeconds,
            vbr = t.attributes.vbr,
            sampleRate = t.attributes.sampleRate,
            bitDepth = t.attributes.bitDepth,
            destinationSubfolder = t.destinationSubfolder,
            localUri = t.localUri,
            addedAtMillis = t.addedAtMillis,
            startedAtMillis = t.startedAtMillis,
            finishedAtMillis = t.finishedAtMillis,
            retryCount = t.retryCount,
        )

        private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
            enumValues<E>().firstOrNull { it.name == name }
    }
}
