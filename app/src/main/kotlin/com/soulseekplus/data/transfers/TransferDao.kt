package com.soulseekplus.data.transfers

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TransferDao {

    @Query("SELECT * FROM transfers ORDER BY addedAtMillis ASC")
    suspend fun all(): List<TransferEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TransferEntity>)

    @Query("DELETE FROM transfers WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
