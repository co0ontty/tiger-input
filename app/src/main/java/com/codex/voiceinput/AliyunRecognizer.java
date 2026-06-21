package com.codex.voiceinput;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

final class AliyunRecognizer {
    private static final int CHUNK_BYTES = 3200;

    private AliyunRecognizer() {
    }

    interface StreamingCallback {
        void onText(String text);

        void onError(Exception error);
    }

    static StreamingSession startStreaming(Context context, StreamingCallback callback) throws IOException {
        String apiKey = AppPrefs.getAliyunApiKey(context);
        if (apiKey.isEmpty()) {
            throw new IOException("未配置阿里云 API Key");
        }
        return new StreamingSession(context.getApplicationContext(), apiKey, callback);
    }

    static String recognize(Context context, byte[] pcm) throws Exception {
        String apiKey = AppPrefs.getAliyunApiKey(context);
        if (apiKey.isEmpty()) {
            throw new IOException("未配置阿里云 API Key");
        }
        if (pcm == null || pcm.length < ModelManager.SAMPLE_RATE / 2) {
            throw new IOException("录音太短");
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        StringBuilder finalText = new StringBuilder();
        AtomicReference<String> partialText = new AtomicReference<>("");
        String taskId = UUID.randomUUID().toString();

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        Request.Builder request = new Request.Builder()
                .url(AppPrefs.ALIYUN_ENDPOINT)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("User-Agent", "VoiceInput/0.1");
        String workspace = AppPrefs.getAliyunWorkspace(context);
        if (!workspace.isEmpty()) {
            request.addHeader("X-DashScope-WorkSpace", workspace);
        }

        WebSocket socket = client.newWebSocket(request.build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(runTaskJson(taskId));
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject root = new JSONObject(text);
                    JSONObject header = root.optJSONObject("header");
                    String event = header == null ? "" : header.optString("event");
                    if ("task-started".equals(event)) {
                        new Thread(() -> sendAudioAndFinish(webSocket, pcm, taskId), "aliyun-audio-send").start();
                    } else if ("result-generated".equals(event)) {
                        JSONObject payload = root.optJSONObject("payload");
                        JSONObject output = payload == null ? null : payload.optJSONObject("output");
                        JSONObject sentence = output == null ? null : output.optJSONObject("sentence");
                        if (sentence != null && !sentence.optBoolean("heartbeat", false)) {
                            String value = sentence.optString("text", "");
                            if (!value.isEmpty()) {
                                if (sentence.optBoolean("sentence_end", false)) {
                                    finalText.append(value);
                                } else {
                                    partialText.set(value);
                                }
                            }
                        }
                    } else if ("task-finished".equals(event)) {
                        done.countDown();
                    } else if ("task-failed".equals(event)) {
                        String message = header == null ? "云端识别失败" : header.optString("error_message", "云端识别失败");
                        error.set(message);
                        done.countDown();
                    }
                } catch (Exception e) {
                    error.set(e.getMessage());
                    done.countDown();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                error.set(t.getMessage());
                done.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                done.countDown();
            }
        });

        boolean completed = done.await(90, TimeUnit.SECONDS);
        socket.close(1000, "done");
        client.dispatcher().executorService().shutdown();
        if (!completed) {
            throw new IOException("云端识别超时");
        }
        if (error.get() != null && !error.get().isEmpty()) {
            throw new IOException(error.get());
        }
        String text = finalText.length() > 0 ? finalText.toString() : partialText.get();
        return text == null ? "" : text;
    }

    static final class StreamingSession {
        private final Object lock = new Object();
        private final OkHttpClient client;
        private final WebSocket socket;
        private final StreamingCallback callback;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<String> error = new AtomicReference<>();
        private final StringBuilder finalText = new StringBuilder();
        private final AtomicReference<String> partialText = new AtomicReference<>("");
        private final List<byte[]> pendingChunks = new ArrayList<>();
        private final String taskId = UUID.randomUUID().toString();
        private volatile boolean started;
        private volatile boolean closed;

        StreamingSession(Context context, String apiKey, StreamingCallback callback) {
            this.callback = callback;
            client = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
            Request.Builder request = new Request.Builder()
                    .url(AppPrefs.ALIYUN_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("User-Agent", "VoiceInput/0.1");
            String workspace = AppPrefs.getAliyunWorkspace(context);
            if (!workspace.isEmpty()) {
                request.addHeader("X-DashScope-WorkSpace", workspace);
            }
            socket = client.newWebSocket(request.build(), new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    webSocket.send(runTaskJson(taskId));
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    handleMessage(text);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    handleError(new IOException(t.getMessage(), t));
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    done.countDown();
                }
            });
        }

        void sendPcm(byte[] pcm) {
            if (pcm == null || pcm.length == 0) {
                return;
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
                if (!started) {
                    pendingChunks.add(pcm);
                    return;
                }
                socket.send(ByteString.of(pcm));
            }
        }

        String finishAndGetText() throws Exception {
            synchronized (lock) {
                if (!closed) {
                    closed = true;
                    socket.send(finishTaskJson(taskId));
                }
            }
            boolean completed = done.await(90, TimeUnit.SECONDS);
            socket.close(1000, "done");
            client.dispatcher().executorService().shutdown();
            if (!completed) {
                throw new IOException("云端识别超时");
            }
            if (error.get() != null && !error.get().isEmpty()) {
                throw new IOException(error.get());
            }
            String text = currentText();
            return text == null ? "" : text;
        }

        void cancel() {
            synchronized (lock) {
                closed = true;
                pendingChunks.clear();
            }
            socket.close(1000, "cancel");
            client.dispatcher().executorService().shutdown();
            done.countDown();
        }

        private void handleMessage(String text) {
            try {
                JSONObject root = new JSONObject(text);
                JSONObject header = root.optJSONObject("header");
                String event = header == null ? "" : header.optString("event");
                if ("task-started".equals(event)) {
                    markStarted();
                } else if ("result-generated".equals(event)) {
                    handleResult(root);
                } else if ("task-finished".equals(event)) {
                    done.countDown();
                } else if ("task-failed".equals(event)) {
                    String message = header == null ? "云端识别失败"
                            : header.optString("error_message", "云端识别失败");
                    handleError(new IOException(message));
                }
            } catch (Exception e) {
                handleError(e);
            }
        }

        private void markStarted() {
            List<byte[]> chunks;
            synchronized (lock) {
                started = true;
                chunks = new ArrayList<>(pendingChunks);
                pendingChunks.clear();
            }
            for (byte[] chunk : chunks) {
                sendPcm(chunk);
            }
        }

        private void handleResult(JSONObject root) {
            JSONObject payload = root.optJSONObject("payload");
            JSONObject output = payload == null ? null : payload.optJSONObject("output");
            JSONObject sentence = output == null ? null : output.optJSONObject("sentence");
            if (sentence == null || sentence.optBoolean("heartbeat", false)) {
                return;
            }
            String value = sentence.optString("text", "");
            if (value.isEmpty()) {
                return;
            }
            if (sentence.optBoolean("sentence_end", false)) {
                finalText.append(value);
                partialText.set("");
            } else {
                partialText.set(value);
            }
            callback.onText(currentText());
        }

        private void handleError(Exception exception) {
            error.set(exception.getMessage());
            callback.onError(exception);
            done.countDown();
        }

        private String currentText() {
            String partial = partialText.get();
            if (finalText.length() == 0) {
                return partial;
            }
            if (partial == null || partial.isEmpty()) {
                return finalText.toString();
            }
            return finalText + partial;
        }
    }

    private static void sendAudioAndFinish(WebSocket webSocket, byte[] pcm, String taskId) {
        for (int offset = 0; offset < pcm.length; offset += CHUNK_BYTES) {
            int length = Math.min(CHUNK_BYTES, pcm.length - offset);
            webSocket.send(ByteString.of(pcm, offset, length));
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        webSocket.send(finishTaskJson(taskId));
    }

    private static String runTaskJson(String taskId) {
        try {
            JSONObject root = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("action", "run-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");

            JSONObject parameters = new JSONObject();
            parameters.put("format", "pcm");
            parameters.put("sample_rate", ModelManager.SAMPLE_RATE);
            parameters.put("disfluency_removal_enabled", false);

            JSONObject payload = new JSONObject();
            payload.put("task_group", "audio");
            payload.put("task", "asr");
            payload.put("function", "recognition");
            payload.put("model", "paraformer-realtime-v2");
            payload.put("parameters", parameters);
            payload.put("input", new JSONObject());

            root.put("header", header);
            root.put("payload", payload);
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String finishTaskJson(String taskId) {
        try {
            JSONObject root = new JSONObject();
            JSONObject header = new JSONObject();
            header.put("action", "finish-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");
            root.put("header", header);
            root.put("payload", new JSONObject().put("input", new JSONObject()));
            return root.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
