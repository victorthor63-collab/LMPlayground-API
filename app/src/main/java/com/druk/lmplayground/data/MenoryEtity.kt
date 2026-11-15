
package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["category"]),
        Index(value = ["updatedAt"])
    ]
)
data class MemoryEntity(
    @PrimaryKey
    val id: String,

    val content: String,

    val category: String = "general",

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis(),

    val importance: Int = 1,

    val source: String = "conversation"
)