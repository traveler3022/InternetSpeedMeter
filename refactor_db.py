import os

# 1. Update Usage.kt
usage_kt_path = "/root/InternetSpeedMeter/app/src/main/java/com/vsp/internetspeedmeter/room/Usage.kt"
usage_kt_content = """package com.vsp.internetspeedmeter.room

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
"""
with open(usage_kt_path, "w") as f:
    f.write(usage_kt_content)


# 2. Update UsageDatabase.kt
usage_db_path = "/root/InternetSpeedMeter/app/src/main/java/com/vsp/internetspeedmeter/room/UsageDatabase.kt"
with open(usage_db_path, "r") as f:
    content = f.read()

content = content.replace("version = 1", "version = 2")
content = content.replace('Usage(date = today, mobile = "0 MB", wifi = "0 MB", total = "0 MB")', 'Usage(date = today, mobile = 0L, wifi = 0L, total = 0L)')

imports_to_add = """import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import com.vsp.internetspeedmeter.util.FormatUtils
"""
content = content.replace("import androidx.room.RoomDatabase\n", "import androidx.room.RoomDatabase\n" + imports_to_add)

migration_code = """        private val MIGRATION_1_2 = object : Migration(1, 2) {
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
        }"""

content = content.replace(".fallbackToDestructiveMigration()", ".addMigrations(MIGRATION_1_2)\n                    .fallbackToDestructiveMigration()")
content = content.replace("        private class RoomCallback : Callback() {", migration_code + "\n\n        private class RoomCallback : Callback() {")

with open(usage_db_path, "w") as f:
    f.write(content)


# 3. Update InternetService.kt
internet_service_path = "/root/InternetSpeedMeter/app/src/main/java/com/vsp/internetspeedmeter/broadcastreceiver/InternetService.kt"
with open(internet_service_path, "r") as f:
    content = f.read()

content = content.replace("""        val mobileStr = FormatUtils.formatBytes(dailyMobileBytes)
        val wifiStr = FormatUtils.formatBytes(dailyWifiBytes)
        val totalStr = FormatUtils.formatBytes(dailyMobileBytes + dailyWifiBytes)
        usageRepository.insert(
            Usage(
                date = lastRecordedDate,
                mobile = mobileStr,
                wifi = wifiStr,
                total = totalStr
            )
        )""", """        usageRepository.insert(
            Usage(
                date = lastRecordedDate,
                mobile = dailyMobileBytes,
                wifi = dailyWifiBytes,
                total = dailyMobileBytes + dailyWifiBytes
            )
        )""")

with open(internet_service_path, "w") as f:
    f.write(content)


# 4. Update UsageAdapter.kt
usage_adapter_path = "/root/InternetSpeedMeter/app/src/main/java/com/vsp/internetspeedmeter/recyclerview/UsageAdapter.kt"
with open(usage_adapter_path, "r") as f:
    content = f.read()

content = content.replace("import com.vsp.internetspeedmeter.databinding.ItemUsageRowBinding\n", "import com.vsp.internetspeedmeter.databinding.ItemUsageRowBinding\nimport com.vsp.internetspeedmeter.util.FormatUtils\n")
content = content.replace("""            binding.tvMobile.text = usage.mobile
            binding.tvWifi.text = usage.wifi
            binding.tvTotal.text = usage.total""", """            binding.tvMobile.text = FormatUtils.formatBytes(usage.mobile)
            binding.tvWifi.text = FormatUtils.formatBytes(usage.wifi)
            binding.tvTotal.text = FormatUtils.formatBytes(usage.total)""")

with open(usage_adapter_path, "w") as f:
    f.write(content)


# 5. Update MainActivity.kt
main_activity_path = "/root/InternetSpeedMeter/app/src/main/java/com/vsp/internetspeedmeter/MainActivity.kt"
with open(main_activity_path, "r") as f:
    content = f.read()

content = content.replace("""            binding.tvTodayMobile.text = todayUsage?.mobile ?: getString(R.string.zero_data)
            binding.tvTodayWifi.text = todayUsage?.wifi ?: getString(R.string.zero_data)
            binding.tvTodayTotal.text = todayUsage?.total ?: getString(R.string.zero_data)""", """            binding.tvTodayMobile.text = todayUsage?.let { FormatUtils.formatBytes(it.mobile) } ?: getString(R.string.zero_data)
            binding.tvTodayWifi.text = todayUsage?.let { FormatUtils.formatBytes(it.wifi) } ?: getString(R.string.zero_data)
            binding.tvTodayTotal.text = todayUsage?.let { FormatUtils.formatBytes(it.total) } ?: getString(R.string.zero_data)""")

content = content.replace("""            // Calculate aggregate 30-day totals accurately using FormatUtils
            val totalMobileBytes = last30Days.sumOf { FormatUtils.parseDataToBytes(it.mobile) }
            val totalWifiBytes = last30Days.sumOf { FormatUtils.parseDataToBytes(it.wifi) }""", """            // Calculate aggregate 30-day totals accurately using Long bytes
            val totalMobileBytes = last30Days.sumOf { it.mobile }
            val totalWifiBytes = last30Days.sumOf { it.wifi }""")

with open(main_activity_path, "w") as f:
    f.write(content)

print("Database refactoring complete")
