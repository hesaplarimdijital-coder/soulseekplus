package com.soulseekplus.data.shares

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** Paylaşım kökü yapılandırması. [treeUri] SAF ağaç URI'si, [level] `ShareLevel` adı. */
@Entity(tableName = "roots")
data class RootEntity(
    @PrimaryKey val id: String,
    val treeUri: String,
    val alias: String,
    val level: String,
    val groupName: String?,
    /** Son başarılı taramanın zamanı (ms); 0 = hiç taranmadı. */
    val scannedAtMillis: Long,
    /** Listeleme sırası. */
    val position: Int,
)

/** Önbellekteki bir dosya. [folder] köke göre `\` ayırıcılı klasör yolu (kök için boş), [docId] SAF belge kimliği. */
@Entity(
    tableName = "files",
    indices = [Index(value = ["rootId", "docId"]), Index(value = ["rootId"])],
)
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rootId: String,
    val folder: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val docId: String,
    val bitrate: Int?,
    @ColumnInfo(name = "duration") val durationSeconds: Int?,
    val vbr: Boolean?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    /** Ses başlığı okundu mu (okunamayan dosyalar her taramada yeniden denenmesin). */
    val attrsRead: Boolean,
)

data class AttributeUpdate(
    val docId: String,
    val bitrate: Int?,
    val durationSeconds: Int?,
    val vbr: Boolean?,
    val sampleRate: Int?,
    val bitDepth: Int?,
)

@Dao
abstract class SharesDao {

    @Query("SELECT * FROM roots ORDER BY position, id")
    abstract suspend fun roots(): List<RootEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRoot(root: RootEntity)

    @Query("UPDATE roots SET scannedAtMillis = :scannedAtMillis WHERE id = :id")
    abstract suspend fun setScanned(id: String, scannedAtMillis: Long)

    @Query("DELETE FROM roots WHERE id = :id")
    abstract suspend fun deleteRoot(id: String)

    @Query("DELETE FROM files WHERE rootId = :rootId")
    abstract suspend fun deleteFiles(rootId: String)

    @Insert
    abstract suspend fun insertFiles(files: List<FileEntity>)

    /** [afterId]'den büyük kimlikli satırlar, kimliğe göre; büyük köklerin sayfa sayfa okunması için. */
    @Query("SELECT * FROM files WHERE rootId = :rootId AND id > :afterId ORDER BY id LIMIT :limit")
    abstract suspend fun filesPage(rootId: String, afterId: Long, limit: Int): List<FileEntity>

    @Query(
        "UPDATE files SET bitrate = :bitrate, duration = :durationSeconds, vbr = :vbr, sampleRate = :sampleRate, " +
            "bitDepth = :bitDepth, attrsRead = 1 WHERE rootId = :rootId AND docId = :docId",
    )
    abstract suspend fun updateAttributes(
        rootId: String,
        docId: String,
        bitrate: Int?,
        durationSeconds: Int?,
        vbr: Boolean?,
        sampleRate: Int?,
        bitDepth: Int?,
    )

    /** Kökün dosyalarını tek işlemde değiştirir ve tarama zamanını yazar. */
    @Transaction
    open suspend fun replaceFiles(rootId: String, files: List<FileEntity>, scannedAtMillis: Long) {
        deleteFiles(rootId)
        for (chunk in files.chunked(INSERT_CHUNK)) insertFiles(chunk)
        setScanned(rootId, scannedAtMillis)
    }

    @Transaction
    open suspend fun updateAttributeBatch(rootId: String, updates: List<AttributeUpdate>) {
        for (u in updates) {
            updateAttributes(rootId, u.docId, u.bitrate, u.durationSeconds, u.vbr, u.sampleRate, u.bitDepth)
        }
    }

    @Transaction
    open suspend fun removeRoot(id: String) {
        deleteFiles(id)
        deleteRoot(id)
    }
}

private const val INSERT_CHUNK = 500

@Database(entities = [RootEntity::class, FileEntity::class], version = 1, exportSchema = true)
abstract class SharesDatabase : RoomDatabase() {

    abstract fun dao(): SharesDao

    companion object {
        @Volatile
        private var instance: SharesDatabase? = null

        fun get(context: Context): SharesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, SharesDatabase::class.java, "shares.db")
                    // Önbellek yeniden üretilebilir (yeniden tarama); şema değişirse sıfırdan başlamak yeterli.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
