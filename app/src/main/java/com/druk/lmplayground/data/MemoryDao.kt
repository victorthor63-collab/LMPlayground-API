
package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    fun getAllMemories(): LiveData<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: String): MemoryEntity?

    @Query("""
        SELECT * FROM memories
        WHERE content LIKE '%' || :query || '%'
        OR category LIKE '%' || :query || '%'
        ORDER BY importance DESC, updatedAt DESC
        LIMIT :limit
    """)
    suspend fun searchMemories(
        query: String,
        limit: Int = 10
    ): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAllMemories()
}
