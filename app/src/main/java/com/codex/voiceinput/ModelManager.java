package com.codex.voiceinput;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class ModelManager {
    static final int SAMPLE_RATE = 16000;
    private static final String MODEL_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/model.int8.onnx";
    private static final String TOKENS_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/tokens.txt";
    private static final long MIN_MODEL_BYTES = 200L * 1024L * 1024L;
    private static final long MIN_TOKENS_BYTES = 100L * 1024L;

    interface DownloadListener {
        void onProgress(String item, long downloaded, long total);

        void onDone();

        void onError(Exception error);
    }

    private ModelManager() {
    }

    static File modelDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "models/sensevoice");
    }

    static File modelFile(Context context) {
        return new File(modelDir(context), "model.int8.onnx");
    }

    static File tokensFile(Context context) {
        return new File(modelDir(context), "tokens.txt");
    }

    static boolean isModelReady(Context context) {
        File model = modelFile(context);
        File tokens = tokensFile(context);
        return model.isFile()
                && model.length() >= MIN_MODEL_BYTES
                && tokens.isFile()
                && tokens.length() >= MIN_TOKENS_BYTES;
    }

    static String statusText(Context context) {
        if (isModelReady(context)) {
            return "本地模型已就绪";
        }
        File model = modelFile(context);
        File tokens = tokensFile(context);
        if (model.exists() || tokens.exists()) {
            return "本地模型不完整，需重新下载";
        }
        return "本地模型未下载";
    }

    static void downloadModel(Context context, DownloadListener listener) {
        try {
            File dir = modelDir(context);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建模型目录: " + dir);
            }
            downloadFile(MODEL_URL, modelFile(context), "model.int8.onnx", listener);
            downloadFile(TOKENS_URL, tokensFile(context), "tokens.txt", listener);
            if (!isModelReady(context)) {
                throw new IOException("模型文件下载完成但校验失败");
            }
            listener.onDone();
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    private static void downloadFile(String url, File destination, String label, DownloadListener listener)
            throws IOException {
        File tmp = new File(destination.getParentFile(), destination.getName() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "VoiceInput/0.1");
        connection.connect();

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("下载失败 " + label + ": HTTP " + code);
        }

        long total = connection.getContentLengthLong();
        long downloaded = 0L;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(tmp)) {
            int read;
            long lastReport = 0L;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                if (downloaded - lastReport > 1024L * 1024L || downloaded == total) {
                    lastReport = downloaded;
                    listener.onProgress(label, downloaded, total);
                }
            }
        } finally {
            connection.disconnect();
        }

        if (destination.exists() && !destination.delete()) {
            throw new IOException("无法替换旧文件: " + destination);
        }
        if (!tmp.renameTo(destination)) {
            throw new IOException("无法保存文件: " + destination);
        }
    }
}
