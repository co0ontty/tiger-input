package com.codex.voiceinput;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

final class AudioCapture {
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int MAX_SECONDS = 60;

    private final Object lock = new Object();
    private AudioRecord recorder;
    private Thread worker;
    private ByteArrayOutputStream buffer;
    private volatile boolean recording;
    private volatile Exception error;
    private volatile LevelListener levelListener;
    private volatile PcmListener pcmListener;

    interface LevelListener {
        void onLevel(float rms, float peak, float tone);
    }

    interface PcmListener {
        void onPcm(byte[] pcm);
    }

    boolean isRecording() {
        return recording;
    }

    void setLevelListener(LevelListener listener) {
        levelListener = listener;
    }

    void setPcmListener(PcmListener listener) {
        pcmListener = listener;
    }

    byte[] getPcmSnapshot() {
        synchronized (lock) {
            return buffer == null ? new byte[0] : buffer.toByteArray();
        }
    }

    void start() throws IOException {
        synchronized (lock) {
            if (recording) {
                return;
            }
            int minBuffer = AudioRecord.getMinBufferSize(ModelManager.SAMPLE_RATE, CHANNEL, ENCODING);
            if (minBuffer <= 0) {
                throw new IOException("无法初始化录音缓冲区");
            }
            int readBufferSize = Math.max(minBuffer, ModelManager.SAMPLE_RATE / 5);
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    ModelManager.SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    readBufferSize * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = null;
                throw new IOException("录音器初始化失败");
            }

            buffer = new ByteArrayOutputStream(ModelManager.SAMPLE_RATE * 2 * 6);
            error = null;
            recording = true;
            recorder.startRecording();
            worker = new Thread(() -> readLoop(readBufferSize), "voice-capture");
            worker.start();
        }
    }

    byte[] stopAndGetPcm() throws IOException {
        Thread toJoin;
        AudioRecord toRelease;
        synchronized (lock) {
            if (!recording && worker == null) {
                return buffer == null ? new byte[0] : buffer.toByteArray();
            }
            recording = false;
            toJoin = worker;
            toRelease = recorder;
            worker = null;
            recorder = null;
        }

        if (toRelease != null) {
            try {
                toRelease.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (toJoin != null) {
            try {
                toJoin.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (toRelease != null) {
            toRelease.release();
        }
        if (error != null) {
            throw new IOException("录音失败", error);
        }
        return buffer == null ? new byte[0] : buffer.toByteArray();
    }

    private void readLoop(int readBufferSize) {
        byte[] readBuffer = new byte[readBufferSize];
        int maxBytes = ModelManager.SAMPLE_RATE * 2 * MAX_SECONDS;
        while (recording) {
            int read = recorder.read(readBuffer, 0, readBuffer.length);
            if (read > 0) {
                synchronized (lock) {
                    buffer.write(readBuffer, 0, read);
                    if (buffer.size() >= maxBytes) {
                        recording = false;
                    }
                }
                publishLevel(readBuffer, read);
                PcmListener listener = pcmListener;
                if (listener != null) {
                    listener.onPcm(Arrays.copyOf(readBuffer, read));
                }
            } else if (read < 0) {
                error = new IOException("AudioRecord.read 返回 " + read);
                recording = false;
            }
        }
    }

    private void publishLevel(byte[] bytes, int byteCount) {
        LevelListener listener = levelListener;
        if (listener == null) {
            return;
        }
        int samples = byteCount / 2;
        if (samples <= 0) {
            return;
        }

        long sumSquares = 0;
        int maxAbs = 0;
        int zeroCrossings = 0;
        int previous = 0;
        boolean hasPrevious = false;

        for (int i = 0; i + 1 < byteCount; i += 2) {
            int lo = bytes[i] & 0xff;
            int hi = bytes[i + 1];
            int sample = (hi << 8) | lo;
            int abs = Math.abs(sample);
            sumSquares += (long) sample * sample;
            if (abs > maxAbs) {
                maxAbs = abs;
            }
            if (hasPrevious && ((sample >= 0 && previous < 0) || (sample < 0 && previous >= 0))) {
                zeroCrossings++;
            }
            previous = sample;
            hasPrevious = true;
        }

        float rms = (float) Math.sqrt(sumSquares / (double) samples) / 32768f;
        float peak = maxAbs / 32768f;
        float zeroCrossRate = zeroCrossings / (float) samples;
        float tone = clamp((zeroCrossRate - 0.015f) / 0.08f);
        listener.onLevel(clamp(rms), clamp(peak), tone);
    }

    private static float clamp(float value) {
        if (value < 0f) {
            return 0f;
        }
        return Math.min(1f, value);
    }
}
