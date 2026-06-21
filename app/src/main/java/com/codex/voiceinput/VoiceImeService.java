package com.codex.voiceinput;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceImeService extends InputMethodService {
    private static final String TAG = "HuniuIme";

    private static final int COLOR_SURFACE = 0xffffffff;
    private static final int COLOR_PANEL = 0xfff8fafc;
    private static final int COLOR_PRIMARY = 0xffdc2626;
    private static final int COLOR_PRIMARY_DARK = 0xff991b1b;
    private static final int COLOR_ACCENT = 0xff2563eb;
    private static final int COLOR_TEXT = 0xff0f172a;
    private static final int COLOR_MUTED = 0xff475569;
    private static final int COLOR_BORDER = 0xffe2e8f0;
    private static final int COLOR_SOFT_RED = 0xfffff1f2;
    private static final int COLOR_SOFT_GREEN = 0xffecfdf5;
    private static final int COLOR_SOFT_BLUE = 0xffeff6ff;
    private static final int IME_PANEL_HEIGHT_DP = 142;
    private static final int CONTROL_HEIGHT_DP = 54;
    private static final int LIVE_PREVIEW_INTERVAL_MS = 1200;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();
    private final AudioCapture audioCapture = new AudioCapture();
    private final Runnable livePreviewTick = new Runnable() {
        @Override
        public void run() {
            runLivePreviewTick();
        }
    };

    private Button micButton;
    private ImageButton deleteButton;
    private ImageButton sendButton;
    private ImageButton hideButton;
    private TextView statusView;
    private WaveformView waveformView;
    private EditorInfo currentEditorInfo;
    private AliyunRecognizer.StreamingSession aliyunStreamingSession;
    private volatile boolean livePreviewActive;
    private volatile boolean livePreviewDecodeRunning;
    private String livePreviewText = "";
    private int lastPreviewBytes;

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        makeImeWindowOpaque();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(14));
        panel.setBackgroundColor(COLOR_PANEL);
        panel.setMinimumHeight(dp(IME_PANEL_HEIGHT_DP));
        panel.setClipToPadding(false);
        panel.setClipChildren(false);
        panel.setFocusable(false);
        panel.setFocusableInTouchMode(false);

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_BORDER);
        panel.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setFocusable(false);
        topRow.setFocusableInTouchMode(false);

        TextView brandView = new TextView(this);
        brandView.setText("虎妞");
        brandView.setTextSize(13);
        brandView.setTypeface(Typeface.DEFAULT_BOLD);
        brandView.setTextColor(COLOR_PRIMARY_DARK);
        brandView.setGravity(Gravity.CENTER);
        brandView.setBackground(roundedBackground(COLOR_SOFT_RED, COLOR_SOFT_RED, dp(12), 0));
        topRow.addView(brandView, new LinearLayout.LayoutParams(dp(58), dp(34)));

        waveformView = new WaveformView(this);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(0, dp(34), 1f);
        waveParams.setMargins(dp(8), 0, dp(8), 0);
        topRow.addView(waveformView, waveParams);

        statusView = new TextView(this);
        statusView.setText("本地");
        statusView.setTextSize(12);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setTextColor(COLOR_MUTED);
        statusView.setGravity(Gravity.CENTER);
        statusView.setMinHeight(dp(34));
        statusView.setFocusable(false);
        statusView.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(dp(52), dp(34));
        statusParams.setMargins(0, 0, dp(8), 0);
        topRow.addView(statusView, statusParams);

        hideButton = topIconButton(R.drawable.ic_keyboard_hide, Color.WHITE, COLOR_MUTED, COLOR_BORDER, "收起键盘");
        topRow.addView(hideButton, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout.LayoutParams topRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        topRowParams.setMargins(0, dp(8), 0, 0);
        panel.addView(topRow, topRowParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(8), 0, 0);
        controls.setClipToPadding(false);
        controls.setClipChildren(false);
        controls.setFocusable(false);
        controls.setFocusableInTouchMode(false);

        micButton = keyButton("语音", COLOR_PRIMARY, Color.WHITE, COLOR_PRIMARY);
        deleteButton = iconButton(R.drawable.ic_backspace, Color.WHITE, COLOR_TEXT, COLOR_BORDER, "删除");
        sendButton = iconButton(R.drawable.ic_send, COLOR_ACCENT, Color.WHITE, COLOR_ACCENT, "发送");

        controls.addView(micButton, keyParams(1f, 0));
        controls.addView(deleteButton, iconParams(dp(10)));
        controls.addView(sendButton, iconParams(dp(10)));
        panel.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(CONTROL_HEIGHT_DP + 8)));

        micButton.setOnClickListener(v -> toggleRecording());
        deleteButton.setOnClickListener(v -> deleteBeforeCursor());
        sendButton.setOnClickListener(v -> performSendAction());
        hideButton.setOnClickListener(v -> hideKeyboard());
        updateStatusIdle();
        return panel;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        currentEditorInfo = attribute;
        Log.d(TAG, "onStartInput restarting=" + restarting
                + " inputType=" + (attribute == null ? 0 : attribute.inputType)
                + " imeOptions=" + (attribute == null ? 0 : attribute.imeOptions));
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        currentEditorInfo = info;
        Log.d(TAG, "onStartInputView restarting=" + restarting
                + " inputType=" + (info == null ? 0 : info.inputType)
                + " imeOptions=" + (info == null ? 0 : info.imeOptions));
        updateStatusIdle();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        Log.d(TAG, "onFinishInputView finishingInput=" + finishingInput);
        cancelRecordingIfNeeded();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        Log.d(TAG, "onFinishInput");
        super.onFinishInput();
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        Log.d(TAG, "onWindowShown");
    }

    @Override
    public void onWindowHidden() {
        Log.d(TAG, "onWindowHidden");
        super.onWindowHidden();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return true;
    }

    @Override
    public void onDestroy() {
        try {
            audioCapture.stopAndGetPcm();
        } catch (Exception ignored) {
        }
        LocalSherpaRecognizer.get(this).release();
        previewExecutor.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }

    private Button keyButton(String text, int fill, int textColor, int stroke) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(CONTROL_HEIGHT_DP));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rippleBackground(fill, stroke, dp(16)));
        button.setElevation(dp(1));
        button.setFocusable(false);
        button.setFocusableInTouchMode(false);
        return button;
    }

    private LinearLayout.LayoutParams keyParams(float weight, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(CONTROL_HEIGHT_DP), weight);
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
    }

    private ImageButton iconButton(int iconRes, int fill, int iconColor, int stroke, String label) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setImageTintList(ColorStateList.valueOf(iconColor));
        button.setBackground(rippleBackground(fill, stroke, dp(16)));
        button.setPadding(dp(15), dp(15), dp(15), dp(15));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setContentDescription(label);
        button.setElevation(dp(1));
        button.setFocusable(false);
        button.setFocusableInTouchMode(false);
        return button;
    }

    private ImageButton topIconButton(int iconRes, int fill, int iconColor, int stroke, String label) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconRes);
        button.setImageTintList(ColorStateList.valueOf(iconColor));
        button.setBackground(rippleBackground(fill, stroke, dp(12)));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setContentDescription(label);
        button.setFocusable(false);
        button.setFocusableInTouchMode(false);
        return button;
    }

    private LinearLayout.LayoutParams iconParams(int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(CONTROL_HEIGHT_DP), dp(CONTROL_HEIGHT_DP));
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
    }

    private void toggleRecording() {
        if (audioCapture.isRecording()) {
            stopAndRecognize();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        Log.d(TAG, "startRecording");
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("请先在虎妞输入法设置页授予麦克风权限");
            openSettings();
            return;
        }
        if (AppPrefs.MODE_LOCAL.equals(AppPrefs.getMode(this)) && !ModelManager.isModelReady(this)) {
            toast("请先下载本地模型");
            openSettings();
            return;
        }
        if (AppPrefs.MODE_ALIYUN.equals(AppPrefs.getMode(this)) && !AppPrefs.isAliyunReady(this)) {
            toast("请先配置阿里云 API Key");
            openSettings();
            return;
        }
        try {
            startStreamingSessionIfNeeded();
            audioCapture.setLevelListener((rms, peak, tone) -> main.post(() -> {
                if (waveformView != null && audioCapture.isRecording()) {
                    waveformView.updateLevel(rms, peak, tone);
                }
            }));
            audioCapture.start();
            micButton.setText("停止");
            micButton.setBackground(rippleBackground(COLOR_PRIMARY, COLOR_PRIMARY));
            micButton.setElevation(dp(1));
            statusView.setText("录音");
            statusView.setTextColor(0xff991b1b);
            statusView.setBackground(roundedBackground(COLOR_SOFT_RED, COLOR_SOFT_RED, dp(12), 0));
            if (waveformView != null) {
                waveformView.start(isReducedMotionEnabled());
            }
            startLivePreview();
        } catch (Exception e) {
            toast("录音失败：" + e.getMessage());
            updateStatusIdle();
        }
    }

    private void stopAndRecognize() {
        Log.d(TAG, "stopAndRecognize");
        setButtonsEnabled(false);
        stopLivePreview(false);
        audioCapture.setLevelListener(null);
        AliyunRecognizer.StreamingSession cloudSession = aliyunStreamingSession;
        aliyunStreamingSession = null;
        if (waveformView != null) {
            waveformView.stop();
        }
        statusView.setText("识别");
        statusView.setTextColor(COLOR_ACCENT);
        statusView.setBackground(roundedBackground(COLOR_SOFT_BLUE, COLOR_SOFT_BLUE, dp(12), 0));
        executor.execute(() -> {
            try {
                byte[] pcm = audioCapture.stopAndGetPcm();
                audioCapture.setPcmListener(null);
                String text;
                if (cloudSession != null) {
                    text = cloudSession.finishAndGetText();
                } else if (AppPrefs.MODE_ALIYUN.equals(AppPrefs.getMode(this))) {
                    text = AliyunRecognizer.recognize(this, pcm);
                } else {
                    text = LocalSherpaRecognizer.get(this).recognizePcm16(pcm);
                }
                String clean = normalizeText(text);
                Log.d(TAG, "recognitionFinished chars=" + clean.length());
                main.post(() -> {
                    if (clean.isEmpty()) {
                        toast("没有识别到文字");
                        clearComposingPreview();
                    } else {
                        commitFinalText(clean);
                    }
                    setButtonsEnabled(true);
                    updateStatusIdle();
                });
            } catch (Exception e) {
                main.post(() -> {
                    toast("识别失败：" + e.getMessage());
                    setButtonsEnabled(true);
                    updateStatusIdle();
                });
            }
        });
    }

    private void deleteBeforeCursor() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            ic.commitText("", 1);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    private void startStreamingSessionIfNeeded() throws Exception {
        livePreviewText = "";
        if (AppPrefs.MODE_ALIYUN.equals(AppPrefs.getMode(this))) {
            AliyunRecognizer.StreamingSession session = AliyunRecognizer.startStreaming(
                    this,
                    new AliyunRecognizer.StreamingCallback() {
                        @Override
                        public void onText(String text) {
                            String clean = normalizeText(text);
                            main.post(() -> {
                                if (!clean.isEmpty() && !clean.equals(livePreviewText)) {
                                    livePreviewText = clean;
                                    showComposingText(clean);
                                }
                            });
                        }

                        @Override
                        public void onError(Exception error) {
                            main.post(() -> {
                                if (audioCapture.isRecording()) {
                                    toast("云端识别失败：" + error.getMessage());
                                }
                            });
                        }
                    });
            aliyunStreamingSession = session;
            audioCapture.setPcmListener(session::sendPcm);
        } else {
            aliyunStreamingSession = null;
            audioCapture.setPcmListener(null);
        }
    }

    private void hideKeyboard() {
        cancelRecordingIfNeeded();
        requestHideSelf(0);
    }

    private void cancelRecordingIfNeeded() {
        if (!audioCapture.isRecording()) {
            return;
        }
        audioCapture.setLevelListener(null);
        audioCapture.setPcmListener(null);
        stopLivePreview(true);
        if (aliyunStreamingSession != null) {
            aliyunStreamingSession.cancel();
            aliyunStreamingSession = null;
        }
        try {
            audioCapture.stopAndGetPcm();
        } catch (Exception ignored) {
        }
        setButtonsEnabled(true);
        updateStatusIdle();
    }

    private void startLivePreview() {
        livePreviewText = "";
        lastPreviewBytes = 0;
        livePreviewDecodeRunning = false;
        livePreviewActive = AppPrefs.MODE_LOCAL.equals(AppPrefs.getMode(this));
        main.removeCallbacks(livePreviewTick);
        if (livePreviewActive) {
            main.postDelayed(livePreviewTick, LIVE_PREVIEW_INTERVAL_MS);
        }
    }

    private void stopLivePreview(boolean clearPreview) {
        livePreviewActive = false;
        main.removeCallbacks(livePreviewTick);
        if (clearPreview) {
            clearComposingPreview();
        }
    }

    private void runLivePreviewTick() {
        if (!livePreviewActive || !audioCapture.isRecording()) {
            return;
        }
        byte[] snapshot = audioCapture.getPcmSnapshot();
        int minBytes = ModelManager.SAMPLE_RATE;
        int minGrowthBytes = ModelManager.SAMPLE_RATE / 2;
        if (snapshot.length >= minBytes
                && snapshot.length - lastPreviewBytes >= minGrowthBytes
                && !livePreviewDecodeRunning) {
            lastPreviewBytes = snapshot.length;
            livePreviewDecodeRunning = true;
            previewExecutor.execute(() -> {
                try {
                    String clean = normalizeText(LocalSherpaRecognizer.get(this).recognizePcm16(snapshot));
                    main.post(() -> {
                        livePreviewDecodeRunning = false;
                        if (livePreviewActive && !clean.isEmpty() && !clean.equals(livePreviewText)) {
                            livePreviewText = clean;
                            showComposingText(clean);
                        }
                    });
                } catch (Exception ignored) {
                    main.post(() -> livePreviewDecodeRunning = false);
                }
            });
        }
        if (livePreviewActive) {
            main.postDelayed(livePreviewTick, LIVE_PREVIEW_INTERVAL_MS);
        }
    }

    private void showComposingText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.setComposingText(text, 1);
        }
    }

    private void commitFinalText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        if (!livePreviewText.isEmpty()) {
            ic.setComposingText(text, 1);
            ic.finishComposingText();
        } else {
            ic.commitText(text, 1);
        }
        livePreviewText = "";
    }

    private void clearComposingPreview() {
        if (livePreviewText.isEmpty()) {
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.setComposingText("", 1);
            ic.finishComposingText();
        }
        livePreviewText = "";
    }

    private void performSendAction() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        int action = EditorInfo.IME_ACTION_SEND;
        if (currentEditorInfo != null) {
            int requested = currentEditorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
            if (requested != EditorInfo.IME_ACTION_NONE && requested != EditorInfo.IME_ACTION_UNSPECIFIED) {
                action = requested;
            }
        }
        boolean ok = ic.performEditorAction(action);
        if (!ok) {
            ok = ic.performEditorAction(EditorInfo.IME_ACTION_DONE);
        }
        if (!ok) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    private void updateStatusIdle() {
        if (micButton != null) {
            micButton.setText("语音");
            micButton.setBackground(rippleBackground(COLOR_PRIMARY, COLOR_PRIMARY, dp(16)));
            micButton.setElevation(dp(1));
        }
        if (statusView != null) {
            boolean aliyun = AppPrefs.MODE_ALIYUN.equals(AppPrefs.getMode(this));
            statusView.setText(aliyun ? "云端" : "本地");
            statusView.setTextColor(aliyun ? COLOR_ACCENT : 0xff047857);
            statusView.setBackground(roundedBackground(aliyun ? COLOR_SOFT_BLUE : COLOR_SOFT_GREEN,
                    aliyun ? COLOR_SOFT_BLUE : COLOR_SOFT_GREEN,
                    dp(12),
                    0));
        }
        if (waveformView != null) {
            waveformView.stop();
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        micButton.setEnabled(enabled);
        deleteButton.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        hideButton.setEnabled(true);
        float alpha = enabled ? 1f : 0.48f;
        micButton.setAlpha(alpha);
        deleteButton.setAlpha(alpha);
        sendButton.setAlpha(alpha);
        hideButton.setAlpha(1f);
    }

    private void openSettings() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void makeImeWindowOpaque() {
        if (getWindow() != null && getWindow().getWindow() != null) {
            Window window = getWindow().getWindow();
            window.setBackgroundDrawable(new ColorDrawable(COLOR_PANEL));
        }
    }

    private boolean isReducedMotionEnabled() {
        try {
            return Settings.Global.getFloat(getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f) == 0f;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("<|zh|>", "")
                .replace("<|en|>", "")
                .replace("<|ja|>", "")
                .replace("<|ko|>", "")
                .replace("<|yue|>", "")
                .trim();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private Drawable roundedBackground(int fill, int stroke, int radius, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, stroke);
        }
        return drawable;
    }

    private Drawable rippleBackground(int fill, int stroke) {
        return rippleBackground(fill, stroke, dp(8));
    }

    private Drawable rippleBackground(int fill, int stroke, int radius) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(fill);
        content.setCornerRadius(radius);
        content.setStroke(dp(1), stroke);
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), content, null);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class WaveformView extends View {
        private static final int BAR_COUNT = 25;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float[] levels = new float[BAR_COUNT];
        private boolean recording;
        private boolean reducedMotion;
        private float smoothedLevel;
        private float toneAmount;

        WaveformView(android.content.Context context) {
            super(context);
            setWillNotDraw(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
        }

        void start(boolean reducedMotion) {
            this.recording = true;
            this.reducedMotion = reducedMotion;
            smoothedLevel = 0f;
            toneAmount = 0.35f;
            for (int i = 0; i < levels.length; i++) {
                levels[i] = 0.08f;
            }
            invalidate();
        }

        void updateLevel(float rms, float peak, float tone) {
            if (!recording) {
                return;
            }
            float raw = clampLevel(rms * 9f + peak * 0.75f);
            float energy = (float) Math.sqrt(raw);
            if (reducedMotion) {
                energy *= 0.72f;
            }
            smoothedLevel = smoothedLevel * 0.58f + energy * 0.42f;
            toneAmount = toneAmount * 0.70f + clampLevel(tone) * 0.30f;

            for (int i = 0; i < levels.length - 1; i++) {
                levels[i] = levels[i + 1] * 0.94f;
            }
            levels[levels.length - 1] = smoothedLevel;
            invalidate();
        }

        void stop() {
            recording = false;
            reducedMotion = false;
            smoothedLevel = 0f;
            toneAmount = 0f;
            for (int i = 0; i < levels.length; i++) {
                levels[i] = 0f;
            }
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            float density = getResources().getDisplayMetrics().density;
            float barWidth = 3f * density;
            float defaultGap = 4f * density;
            float gap = defaultGap;
            float totalWidth = BAR_COUNT * barWidth + (BAR_COUNT - 1) * gap;
            float maxWidth = width * 0.90f;
            if (totalWidth > maxWidth) {
                gap = Math.max(2.4f * density, (maxWidth - BAR_COUNT * barWidth) / (BAR_COUNT - 1));
                totalWidth = BAR_COUNT * barWidth + (BAR_COUNT - 1) * gap;
            }
            float startX = (width - totalWidth) / 2f;
            float centerY = height / 2f;
            float maxBarHeight = height * 0.72f;
            float minBarHeight = Math.max(4f * density, height * 0.16f);
            float mid = (BAR_COUNT - 1) / 2f;

            paint.setColor(recording ? 0xffdc2626 : 0xffcbd5e1);
            for (int i = 0; i < BAR_COUNT; i++) {
                float strength;
                if (!recording) {
                    strength = 0.18f + (i % 3) * 0.04f;
                } else if (reducedMotion) {
                    strength = 0.16f + levels[i] * 0.72f;
                } else {
                    float centerBoost = 1f - Math.abs(i - mid) / Math.max(1f, mid);
                    float toneShape = 0.82f + 0.24f
                            * (float) Math.sin(i * (0.52f + toneAmount * 0.88f) + toneAmount * 2.1f);
                    strength = 0.12f + levels[i] * (0.58f + centerBoost * 0.42f) * toneShape;
                }
                strength = clampLevel(strength);
                float barHeight = minBarHeight + (maxBarHeight - minBarHeight) * strength;
                float left = startX + i * (barWidth + gap);
                rect.set(left, centerY - barHeight / 2f, left + barWidth, centerY + barHeight / 2f);
                canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);
            }
        }

        private static float clampLevel(float value) {
            if (value < 0f) {
                return 0f;
            }
            return Math.min(1f, value);
        }
    }
}
