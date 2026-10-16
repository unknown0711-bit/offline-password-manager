package com.example.offlinevault.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultEntryDao {
    @Query("SELECT * FROM vault_entries ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<VaultEntry>>

    @Query("SELECT * FROM vault_entries WHERE id = :entryId LIMIT 1")
    suspend fun getById(entryId: String): VaultEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: VaultEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<VaultEntry>)

    @Update
    suspend fun update(entry: VaultEntry)

    @Delete
    suspend fun delete(entry: VaultEntry)

    @Query("DELETE FROM vault_entries WHERE id = :entryId")
    suspend fun deleteById(entryId: String): Int
}
