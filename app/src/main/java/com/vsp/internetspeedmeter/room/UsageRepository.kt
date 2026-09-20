package com.vsp.internetspeedmeter.room

import android.content.Context
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsageRepository(context: Context) {
    private val usageDao: UsageDao = UsageDatabase.getInstance(context).usageDao()
    val allUsage: LiveData<List<Usage>> = usageDao.getAllUsage()
    val allUsageFlow: Flow<List<Usage>> = usageDao.getAllUsageFlow()

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun upsert(usage: Usage) = withContext(Dispatchers.IO) {
        usageDao.upsert(usage)
    }

    suspend fun getUsageByDate(date: String): Usage? = withContext(Dispatchers.IO) {
        usageDao.getUsageByDate(date)
    }

    suspend fun getMonthlyMobileExcluding(monthYear: String, excludeDate: String): Long =
        withContext(Dispatchers.IO) {
            usageDao.getMonthlyMobileExcluding(monthYear, excludeDate)
        }

    suspend fun delete(usage: Usage) = withContext(Dispatchers.IO) {
        usageDao.delete(usage)
    }

    suspend fun deleteAllNotes() = withContext(Dispatchers.IO) {
        usageDao.deleteAll()
    }

    // Fire-and-forget helpers for background tasks
    fun insert(usage: Usage) {
        repositoryScope.launch {
            usageDao.upsert(usage)
        }
    }

    fun update(usage: Usage) {
        repositoryScope.launch {
            usageDao.upsert(usage)
        }
    }

    fun deleteAsync(usage: Usage) {
        repositoryScope.launch {
            usageDao.delete(usage)
        }
    }
}
