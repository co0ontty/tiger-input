package com.codex.voiceinput;

import android.content.Context;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;
import java.io.IOException;

final class LocalSherpaRecognizer {
    private static LocalSherpaRecognizer instance;

    private final Context context;
    private OfflineRecognizer recognizer;

    private LocalSherpaRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    static synchronized LocalSherpaRecognizer get(Context context) {
        if (instance == null) {
            instance = new LocalSherpaRecognizer(context);
        }
        return instance;
    }

    synchronized String recognizePcm16(byte[] pcm) throws Exception {
        if (pcm == null || pcm.length < ModelManager.SAMPLE_RATE / 2) {
            throw new IOException("录音太短");
        }
        ensureRecognizer();
        float[] samples = pcm16ToFloat(pcm);
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(samples, ModelManager.SAMPLE_RATE);
            recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);
            return result == null ? "" : result.getText();
        } finally {
            stream.release();
        }
    }

    synchronized void release() {
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
        }
    }

    private void ensureRecognizer() throws IOException {
        if (recognizer != null) {
            return;
        }
        if (!ModelManager.isModelReady(context)) {
            throw new IOException("本地模型未就绪");
        }

        File model = ModelManager.modelFile(context);
        File tokens = ModelManager.tokensFile(context);

        OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
        senseVoice.setModel(model.getAbsolutePath());
        senseVoice.setLanguage("auto");
        senseVoice.setUseInverseTextNormalization(true);

        OfflineModelConfig modelConfig = new OfflineModelConfig();
        modelConfig.setSenseVoice(senseVoice);
        modelConfig.setTokens(tokens.getAbsolutePath());
        modelConfig.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        modelConfig.setProvider("cpu");
        modelConfig.setDebug(false);

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(new FeatureConfig(ModelManager.SAMPLE_RATE, 80, 0.0f));
        config.setModelConfig(modelConfig);
        config.setDecodingMethod("greedy_search");
        recognizer = new OfflineRecognizer(null, config);
    }

    private static float[] pcm16ToFloat(byte[] pcm) {
        int sampleCount = pcm.length / 2;
        float[] samples = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1];
            short value = (short) ((hi << 8) | lo);
            samples[i] = value / 32768.0f;
        }
        return samples;
    }
}
