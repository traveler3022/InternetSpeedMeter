package com.vsp.internetspeedmeter.Room

import android.content.Context
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class UsageRepository(context: Context) {
    private val usageDao: UsageDao = UsageDatabase.getInstance(context).usageDao()
    val allUsage: LiveData<List<Usage>> = usageDao.getAllUsage()
    val allUsageFlow: Flow<List<Usage>> = usageDao.getAllUsageFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    fun insert(usage: Usage) {
        repositoryScope.launch {
            usageDao.insert(usage)
        }
    }

    fun update(usage: Usage) {
        repositoryScope.launch {
            usageDao.update(usage)
        }
    }

    fun delete(usage: Usage) {
        repositoryScope.launch {
            usageDao.delete(usage)
        }
    }

    fun deleteAllNotes() {
        repositoryScope.launch {
            usageDao.deleteAll()
        }
    }
}
