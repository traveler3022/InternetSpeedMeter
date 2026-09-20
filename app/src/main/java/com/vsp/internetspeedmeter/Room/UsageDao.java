package com.vsp.internetspeedmeter.Room;

import java.util.List;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import androidx.room.OnConflictStrategy;

@Dao
public interface UsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Usage usage);

    @Update
    void update(Usage usage);

    @Delete
    void delete(Usage usage);

    @Query("DELETE FROM Usage_Table")
    void deleteAll();

    @Query("SELECT * FROM Usage_Table")
    LiveData<List<Usage>> getAllUsage();
}
