package com.vsp.internetspeedmeter.Room;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = Usage.class, version = 1)
public abstract class UsageDatabase extends RoomDatabase {

    private static UsageDatabase instance;

    public abstract UsageDao usageDao();

    public static synchronized UsageDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                    UsageDatabase.class, "usage_database")
                    .fallbackToDestructiveMigration()
                    .addCallback(roomCallback)
                    .build();
        }
        return instance;
    }

    public static final RoomDatabase.Callback roomCallback = new RoomDatabase.Callback() {
        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
            // Seed today's row so the list is not empty on first launch. The insert
            // replaces on conflict, so no insert/update fallback dance is needed.
            UsageRepository.DB_EXECUTOR.execute(() -> {
                String today = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                        .format(Calendar.getInstance().getTime());
                instance.usageDao().insert(new Usage(today, "0 B", "0 B", "0 B"));
            });
        }
    };
}
