package com.vsp.internetspeedmeter.Room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Usage_Table")
data class Usage(
    @PrimaryKey
    val date: String,
    val mobile: String = "0 MB",
    val wifi: String = "0 MB",
    val total: String = "0 MB"
)
