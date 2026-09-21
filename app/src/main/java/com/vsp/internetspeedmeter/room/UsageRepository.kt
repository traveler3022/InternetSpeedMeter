package com.vsp.internetspeedmeter.room

import android.content.Context
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UsageRepository(context: Context) {
    private val usageDao: UsageDao = UsageDatabase.getInstance(context).usageDao()
    val allUsage: LiveData<List<Usage>> = usageDao.getAllUsage()
    val allUsageFlow: Flow<List<Usage>> = usageDao.getAllUsageFlow()

    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    private val writeMutex = Mutex()

    suspend fun upsert(usage: Usage) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            usageDao.upsert(usage)
        }
    }

    suspend fun insertAllIgnore(usages: List<Usage>) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            usageDao.insertAllIgnore(usages)
        }
    }

    suspend fun getUsageByDate(date: String): Usage? = withContext(Dispatchers.IO) {
        usageDao.getUsageByDate(date)
    }

    suspend fun getMonthlyMobileExcluding(monthYear: String, excludeDate: String): Long =
        withContext(Dispatchers.IO) {
            usageDao.getMonthlyMobileExcluding(monthYear, excludeDate)
        }

    suspend fun delete(usage: Usage) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            usageDao.delete(usage)
        }
    }

    suspend fun deleteAllNotes() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            usageDao.deleteAll()
        }
    }

    // Fire-and-forget helpers for background tasks
    fun insert(usage: Usage) {
        repositoryScope.launch {
            writeMutex.withLock {
                usageDao.upsert(usage)
            }
        }
    }

    fun update(usage: Usage) {
        repositoryScope.launch {
            writeMutex.withLock {
                usageDao.upsert(usage)
            }
        }
    }

    fun deleteAsync(usage: Usage) {
        repositoryScope.launch {
            writeMutex.withLock {
                usageDao.delete(usage)
            }
        }
    }
}
