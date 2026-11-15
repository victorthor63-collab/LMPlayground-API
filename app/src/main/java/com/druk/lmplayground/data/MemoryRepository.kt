
package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import java.util.UUID

class MemoryRepository(
    private val memoryDao: MemoryDao
) {

    fun getAllMemories(): LiveData<List<MemoryEntity>> {
        return memoryDao.getAllMemories()
    }

    suspend fun saveMemory(
        content: String,
        category: String = "general",
        importance: Int = 1,
        source: String = "conversation"
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val memory = MemoryEntity(
            id = id,
            content = content,
            category = category,
            createdAt = now,
            updatedAt = now,
            importance = importance.coerceIn(1, 5),
            source = source
        )

        memoryDao.insertMemory(memory)
        return id
    }

    suspend fun searchMemories(
        query: String,
        limit: Int = 10
    ): List<MemoryEntity> {
        if (query.isBlank()) return emptyList()
        return memoryDao.searchMemories(query, limit.coerceIn(1, 50))
    }

    suspend fun deleteMemory(id: String) {
        memoryDao.deleteMemory(id)
    }

    suspend fun deleteAllMemories() {
        memoryDao.deleteAllMemories()
    }
}
