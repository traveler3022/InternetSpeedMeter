package com.vsp.internetspeedmeter.room

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {

    @Upsert
    suspend fun upsert(usage: Usage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usage: Usage)

    @Update
    suspend fun update(usage: Usage)

    @Delete
    suspend fun delete(usage: Usage)

    @Query("DELETE FROM Usage_Table")
    suspend fun deleteAll()

    @Query("SELECT * FROM Usage_Table")
    fun getAllUsage(): LiveData<List<Usage>>

    @Query("SELECT * FROM Usage_Table")
    fun getAllUsageFlow(): Flow<List<Usage>>

    @Query("SELECT * FROM Usage_Table WHERE date = :date LIMIT 1")
    suspend fun getUsageByDate(date: String): Usage?

    /** Mobile bytes recorded in one "MM-yyyy" bucket, excluding [excludeDate]. */
    @Query("SELECT COALESCE(SUM(mobile), 0) FROM Usage_Table WHERE substr(date, 4) = :monthYear AND date != :excludeDate")
    suspend fun getMonthlyMobileExcluding(monthYear: String, excludeDate: String): Long
}
