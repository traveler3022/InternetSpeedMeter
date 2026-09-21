package com.vsp.internetspeedmeter

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vsp.internetspeedmeter.room.Usage
import com.vsp.internetspeedmeter.room.UsageDao
import com.vsp.internetspeedmeter.room.UsageDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest=Config.NONE, sdk = [30])
class UsageDatabaseTest {
    private lateinit var usageDao: UsageDao
    private lateinit var db: UsageDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, UsageDatabase::class.java).allowMainThreadQueries().build()
        usageDao = db.usageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadUsage() = runBlocking {
        val usage = Usage("21-09-2026", 1000L, 500L, 1500L)
        usageDao.insert(usage)
        val loaded = usageDao.getUsageByDate("21-09-2026")
        assertNotNull(loaded)
        assertEquals(1000L, loaded?.mobile)
        assertEquals(500L, loaded?.wifi)
        assertEquals(1500L, loaded?.total)
    }

    @Test
    fun testUpdateUsage() = runBlocking {
        val usage = Usage("21-09-2026", 100L, 200L, 300L)
        usageDao.insert(usage)
        usageDao.insert(Usage("21-09-2026", 500L, 500L, 1000L)) // This should REPLACE due to OnConflictStrategy.REPLACE
        val loaded = usageDao.getUsageByDate("21-09-2026")
        assertEquals(1000L, loaded?.total)
    }

    @Test
    fun testDeleteAll() = runBlocking {
        usageDao.insert(Usage("01-01-2020", 1L, 1L, 2L))
        usageDao.insert(Usage("02-01-2020", 2L, 2L, 4L))
        usageDao.deleteAll()
        val loaded = usageDao.getUsageByDate("01-01-2020")
        assertNull(loaded)
    }

    @Test
    fun testGetMonthlyMobileExcluding() = runBlocking {
        usageDao.insert(Usage("20-09-2026", 100L, 0L, 100L))
        usageDao.insert(Usage("21-09-2026", 50L, 0L, 50L))
        usageDao.insert(Usage("22-09-2026", 200L, 0L, 200L))
        
        // Sum mobile bytes where date LIKE '%-09-2026' and date != '21-09-2026'
        val sum = usageDao.getMonthlyMobileExcluding("09-2026", "21-09-2026")
        assertEquals(300L, sum) // 100 + 200
    }
}
