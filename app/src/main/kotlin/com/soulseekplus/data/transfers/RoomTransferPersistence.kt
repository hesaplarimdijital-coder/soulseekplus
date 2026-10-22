package com.soulseekplus.data.transfers

import android.content.Context
import androidx.room.withTransaction
import com.soulseekplus.core.api.TransferPersistence
import com.soulseekplus.core.model.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [TransferPersistence] için Room uygulaması. Veritabanı ilk kullanımda (IO iş parçacığında) açılır. */
class RoomTransferPersistence(context: Context) : TransferPersistence {

    private val appContext: Context = context.applicationContext
    private val db: TransfersDatabase by lazy { TransfersDatabase.get(appContext) }

    override suspend fun loadAll(): List<Transfer> = withContext(Dispatchers.IO) {
        db.transfers().all().mapNotNull { it.toTransfer() }
    }

    /** Toplu yazma tek işlemde yapılır (çok sayıda ilerleme güncellemesi için tek fsync). */
    override suspend fun upsert(transfers: List<Transfer>) {
        if (transfers.isEmpty()) return
        withContext(Dispatchers.IO) {
            val rows = transfers.map(TransferEntity::from)
            db.withTransaction {
                for (chunk in rows.chunked(CHUNK)) db.transfers().insertAll(chunk)
            }
        }
    }

    override suspend fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            db.withTransaction {
                // SQLite değişken sınırı (eski Android'de 999) aşılmasın diye parçalı silme.
                for (chunk in ids.toList().chunked(CHUNK)) db.transfers().deleteByIds(chunk)
            }
        }
    }

    private companion object {
        const val CHUNK = 400
    }
}
