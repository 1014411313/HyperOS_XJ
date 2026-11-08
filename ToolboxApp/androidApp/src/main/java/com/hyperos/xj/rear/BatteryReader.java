package com.hyperos.xj.rear;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class BatteryReader {
    private BatteryReader() {
    }

    public static ChargeState read(Context ctx) {
        ChargeState state = new ChargeState();
        Intent intent = null;
        try {
            intent = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) {
        }

        int levelRaw = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) : 0;
        int scale = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100) : 100;
        if (scale <= 0) scale = 100;
        state.level = Math.max(0, Math.min(100, levelRaw * 100 / scale));

        int mv = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) : 0;
        state.voltageV = mv / 1000.0;

        int tenth = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
        state.tempC = tenth / 10.0;

        int status = intent != null
                ? intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                : BatteryManager.BATTERY_STATUS_UNKNOWN;
        int plugged = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;

        int currentUa = 0;
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            }
        } catch (Throwable ignored) {
        }
        state.currentA = currentUa / 1_000_000.0;
        state.powerW = state.currentA * state.voltageV;
        state.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        state.full = status == BatteryManager.BATTERY_STATUS_FULL || state.level >= 100;
        state.statusText = state.full ? "已充满"
                : state.charging ? "充电中"
                : plugged != 0 ? "已连接"
                : "放电中";

        double absWatts = Math.abs(state.powerW);
        state.stageText = !state.charging ? "未充电"
                : absWatts >= 60 ? "超级快充"
                : absWatts >= 10 ? "快充"
                : state.level >= 85 ? "涓流充电"
                : "充电中";

        long fullUah = readLong("/sys/class/power_supply/battery/charge_full");
        if (state.charging && fullUah > 0 && state.currentA > 0.05) {
            double fullMah = fullUah / 1000.0;
            double remainMah = fullMah * state.level / 100.0;
            double minutes = (fullMah - remainMah) / (state.currentA * 1000.0) * 60.0;
            if (minutes >= 0 && minutes < 600) state.etaMin = (int) minutes;
        }
        return state;
    }

    private static long readLong(String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(new File(path)));
            String line = reader.readLine();
            return line != null ? Long.parseLong(line.trim()) : 0L;
        } catch (Throwable t) {
            return 0L;
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
