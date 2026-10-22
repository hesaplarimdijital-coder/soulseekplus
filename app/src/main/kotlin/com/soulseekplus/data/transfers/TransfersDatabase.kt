package com.soulseekplus.data.transfers

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TransferEntity::class], version = 1, exportSchema = true)
abstract class TransfersDatabase : RoomDatabase() {

    abstract fun transfers(): TransferDao

    companion object {
        private const val NAME = "transfers.db"

        @Volatile
        private var instance: TransfersDatabase? = null

        fun get(context: Context): TransfersDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, TransfersDatabase::class.java, NAME)
                    // Transfer listesi yeniden kurulabilir bir önbellektir; şema uyuşmazlığında uygulamayı çökertme.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
