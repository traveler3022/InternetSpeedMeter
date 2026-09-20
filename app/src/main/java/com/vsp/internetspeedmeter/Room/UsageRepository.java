package com.vsp.internetspeedmeter.Room;

import android.content.Context;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.lifecycle.LiveData;

public class UsageRepository {

    /**
     * AsyncTask, which this used to spawn per write, is deprecated since API 30 and
     * its shared pool gives no ordering guarantee. A single serial executor keeps the
     * daily row's writes in order and off the main thread.
     */
    static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor();

    private final UsageDao usageDao;
    private final LiveData<List<Usage>> allUsage;

    public UsageRepository(Context context) {
        UsageDatabase database = UsageDatabase.getInstance(context);
        usageDao = database.usageDao();
        allUsage = usageDao.getAllUsage();
    }

    /** Upsert: the date is the primary key and the DAO replaces on conflict. */
    public void insert(Usage usage) {
        DB_EXECUTOR.execute(() -> usageDao.insert(usage));
    }

    public void update(Usage usage) {
        DB_EXECUTOR.execute(() -> usageDao.update(usage));
    }

    public void delete(Usage usage) {
        DB_EXECUTOR.execute(() -> usageDao.delete(usage));
    }

    public void deleteAllNotes() {
        DB_EXECUTOR.execute(usageDao::deleteAll);
    }

    public LiveData<List<Usage>> getAllUsage() {
        return allUsage;
    }
}
