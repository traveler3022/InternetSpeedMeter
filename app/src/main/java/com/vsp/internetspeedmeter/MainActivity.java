package com.vsp.internetspeedmeter;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import com.vsp.internetspeedmeter.BroadcastReciever.InternetService;
import com.vsp.internetspeedmeter.Model.DisplayModel;
import com.vsp.internetspeedmeter.Recyclerview.UsageAdapter;
import com.vsp.internetspeedmeter.Room.Usage;
import com.vsp.internetspeedmeter.Room.UsageViewModel;

import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity {
    public static final String TAG = "internetspeed";

    List<String> date = new ArrayList<>(), mobile = new ArrayList<>(), wifi = new ArrayList<>(), total = new ArrayList<>();
    private UsageViewModel viewModel;
    RecyclerView recyclerView;
    UsageAdapter adapter;
    SharedPreferences preferences;
    SharedPreferences.Editor editor;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        recyclerView = findViewById(R.id.recyclervewUsage);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        viewModel = new ViewModelProvider(this).get(UsageViewModel.class);
        adapter = new UsageAdapter(this, new DisplayModel(date, mobile, wifi, total));
        preferences = getSharedPreferences("internetSpeed", MODE_PRIVATE);
        editor = preferences.edit();
        if (!preferences.contains("isStarted")) {
            editor.putBoolean("isStarted", false);
            editor.apply();
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        viewModel.getAllNotes().observe(this, new Observer<List<Usage>>() {
            @Override
            public void onChanged(List<Usage> usages) {
                if (usages == null) return;
                Log.d(TAG, "onChanged: " + usages.size());

                date.clear();
                mobile.clear();
                wifi.clear();
                total.clear();

                for (Usage usage : usages) {
                    date.add(usage.getdate());
                    mobile.add(usage.getMobile());
                    wifi.add(usage.getWifi());
                    total.add(usage.getTotal());
                }

                adapter.notifyDataSetChanged();
            }
        });

        recyclerView.setAdapter(adapter);

        check();
    }

    private void check() {
        if (!preferences.getBoolean("isStarted", false)) {
            editor.putBoolean("isStarted", true);
            editor.apply();
        }

        Intent serviceIntent = new Intent(this, InternetService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

}