package com.vsp.internetspeedmeter.Room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Database(entities = [Usage::class], version = 1, exportSchema = false)
abstract class UsageDatabase : RoomDatabase() {

    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile
        private var INSTANCE: UsageDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): UsageDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UsageDatabase::class.java,
                    "usage_database"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(RoomCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class RoomCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val today = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                            .format(Calendar.getInstance().time)
                        database.usageDao().insert(Usage(date = today, mobile = "0 MB", wifi = "0 MB", total = "0 MB"))
                    }
                }
            }
        }
    }
}
