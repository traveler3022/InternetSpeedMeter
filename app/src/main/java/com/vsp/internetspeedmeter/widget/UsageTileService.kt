package com.vsp.internetspeedmeter.widget

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.vsp.internetspeedmeter.MainActivity
import com.vsp.internetspeedmeter.R
import com.vsp.internetspeedmeter.room.UsageDatabase
import com.vsp.internetspeedmeter.ui.Fmt
import com.vsp.internetspeedmeter.util.CalendarMath
import com.vsp.internetspeedmeter.util.AppCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick-settings tile with today's total; tapping it opens the app. */
@RequiresApi(Build.VERSION_CODES.N)
class UsageTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val today = CalendarMath.dbDate(AppCalendar.today(this@UsageTileService))
            val row = withContext(Dispatchers.IO) {
                try {
                    UsageDatabase.getInstance(this@UsageTileService).usageDao().getUsageByDate(today)
                } catch (_: Exception) {
                    null
                }
            }
            val tile = qsTile ?: return@launch
            val total = Fmt.bytes(this@UsageTileService, (row?.mobile ?: 0L) + (row?.wifi ?: 0L))
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.label = getString(R.string.tile_label)
                tile.subtitle = total
            } else {
                tile.label = total
            }
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
