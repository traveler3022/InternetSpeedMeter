package com.vsp.internetspeedmeter.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Usage_Table")
data class Usage(
    @PrimaryKey
    val date: String,
    val mobile: Long = 0L,
    val wifi: Long = 0L,
    val total: Long = 0L
)
