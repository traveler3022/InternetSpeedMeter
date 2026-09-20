package com.vsp.internetspeedmeter.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import com.vsp.internetspeedmeter.util.FormatUtils
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Database(entities = [Usage::class], version = 2, exportSchema = false)
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
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .addCallback(RoomCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `Usage_Table_new` (`date` TEXT NOT NULL, `mobile` INTEGER NOT NULL, `wifi` INTEGER NOT NULL, `total` INTEGER NOT NULL, PRIMARY KEY(`date`))")
                
                val cursor = database.query("SELECT date, mobile, wifi, total FROM Usage_Table")
                if (cursor.moveToFirst()) {
                    do {
                        val date = cursor.getString(0)
                        val mobileStr = cursor.getString(1)
                        val wifiStr = cursor.getString(2)
                        
                        val mobileBytes = FormatUtils.parseDataToBytes(mobileStr)
                        val wifiBytes = FormatUtils.parseDataToBytes(wifiStr)
                        val totalBytes = mobileBytes + wifiBytes
                        
                        val values = ContentValues().apply {
                            put("date", date)
                            put("mobile", mobileBytes)
                            put("wifi", wifiBytes)
                            put("total", totalBytes)
                        }
                        database.insert("Usage_Table_new", SQLiteDatabase.CONFLICT_REPLACE, values)
                    } while (cursor.moveToNext())
                }
                cursor.close()
                
                database.execSQL("DROP TABLE `Usage_Table`")
                database.execSQL("ALTER TABLE `Usage_Table_new` RENAME TO `Usage_Table`")
            }
        }

        private class RoomCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val today = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                            .format(Calendar.getInstance().time)
                        database.usageDao().insert(Usage(date = today, mobile = 0L, wifi = 0L, total = 0L))
                    }
                }
            }
        }
    }
}
