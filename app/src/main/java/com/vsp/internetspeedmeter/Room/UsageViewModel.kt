package com.vsp.internetspeedmeter.Room

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class UsageViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UsageRepository(application)
    val allNotes: LiveData<List<Usage>> = repository.allUsage

    fun insert(usage: Usage) = viewModelScope.launch {
        repository.upsert(usage)
    }

    fun update(usage: Usage) = viewModelScope.launch {
        repository.upsert(usage)
    }

    fun delete(usage: Usage) = viewModelScope.launch {
        repository.delete(usage)
    }

    fun deleteAllNotes() = viewModelScope.launch {
        repository.deleteAllNotes()
    }
}
