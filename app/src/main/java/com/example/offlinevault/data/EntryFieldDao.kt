package com.example.offlinevault.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryFieldDao {
    @Query(
        """
        SELECT * FROM entry_fields
        WHERE vault_entry_id = :entryId
        ORDER BY display_order ASC, created_at ASC
        """
    )
    fun observeForEntry(entryId: String): Flow<List<EntryField>>

    @Query(
        """
        SELECT * FROM entry_fields
        WHERE vault_entry_id = :entryId
        ORDER BY display_order ASC, created_at ASC
        """
    )
    suspend fun getForEntry(entryId: String): List<EntryField>

    @Query(
        """
        SELECT * FROM entry_fields
        WHERE vault_entry_id = :entryId AND field_key = :fieldKey
        LIMIT 1
        """
    )
    suspend fun getByKey(entryId: String, fieldKey: String): EntryField?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(field: EntryField)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(fields: List<EntryField>)

    @Update
    suspend fun update(field: EntryField)

    @Delete
    suspend fun delete(field: EntryField)

    @Query("DELETE FROM entry_fields WHERE id = :fieldId")
    suspend fun deleteById(fieldId: String): Int

    @Query("DELETE FROM entry_fields WHERE vault_entry_id = :entryId")
    suspend fun deleteForEntry(entryId: String): Int
}
