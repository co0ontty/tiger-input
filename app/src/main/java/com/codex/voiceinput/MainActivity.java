package com.codex.voiceinput;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HUNIU_IME_ID = "com.codex.voiceinput/.VoiceImeService";

    private static final int COLOR_BG = 0xfff8fafc;
    private static final int COLOR_CARD = 0xffffffff;
    private static final int COLOR_PRIMARY = 0xffdc2626;
    private static final int COLOR_PRIMARY_DARK = 0xff991b1b;
    private static final int COLOR_ACCENT = 0xff2563eb;
    private static final int COLOR_TEXT = 0xff0f172a;
    private static final int COLOR_MUTED = 0xff475569;
    private static final int COLOR_BORDER = 0xffe2e8f0;
    private static final int COLOR_SOFT_RED = 0xfffff1f2;
    private static final int COLOR_SOFT_BLUE = 0xffeff6ff;
    private static final int COLOR_SOFT_GREEN = 0xffecfdf5;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AudioCapture testCapture = new AudioCapture();

    private TextView permissionStatus;
    private TextView modelStatus;
    private TextView downloadStatus;
    private TextView currentImeStatus;
    private TextView testStatus;
    private RadioButton localMode;
    private RadioButton aliyunMode;
    private EditText apiKeyInput;
    private EditText workspaceInput;
    private EditText testInput;
    private Button permissionButton;
    private Button downloadButton;
    private Button testRecordButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(COLOR_BG);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(buildContent());
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (testCapture.isRecording()) {
            try {
                testCapture.stopAndGetPcm();
            } catch (Exception ignored) {
            }
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(30));
        scroll.addView(root);

        addHero(root);
        addReadinessPanel(root);
        addTestPanel(root);
        addModePanel(root);
        addCloudPanel(root);
        addSystemPanel(root);
        return scroll;
    }

    private void addHero(LinearLayout root) {
        LinearLayout hero = panel(COLOR_PRIMARY, 0x00ffffff);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("虎妞输入法");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setIncludeFontPadding(false);
        hero.addView(title);

        TextView enName = new TextView(this);
        enName.setText(getString(R.string.brand_name_en));
        enName.setTextSize(15);
        enName.setTextColor(0xeeffffff);
        enName.setPadding(0, dp(8), 0, dp(14));
        hero.addView(enName);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chip("本地优先"));
        chips.addView(chip("小键盘"));
        chips.addView(chip("可接云端"));
        hero.addView(chips);

        root.addView(hero);
    }

    private void addReadinessPanel(LinearLayout root) {
        LinearLayout card = panel(COLOR_CARD, COLOR_BORDER);
        card.addView(sectionTitle("就绪状态"));

        permissionStatus = statusBadge("");
        card.addView(statusRow("麦克风权限", permissionStatus));

        permissionButton = secondaryButton("授予麦克风权限");
        permissionButton.setOnClickListener(v -> requestMicPermission());
        card.addView(permissionButton, fullWidthParams(dp(10)));

        modelStatus = statusBadge("");
        card.addView(statusRow("本地模型", modelStatus));

        downloadStatus = smallText("");
        card.addView(downloadStatus);

        downloadButton = primaryButton("下载本地模型");
        downloadButton.setOnClickListener(v -> startModelDownload());
        card.addView(downloadButton, fullWidthParams(dp(10)));

        root.addView(card);
    }

    private void addTestPanel(LinearLayout root) {
        LinearLayout card = panel(COLOR_CARD, COLOR_BORDER);
        card.addView(sectionTitle("测试区"));

        TextView label = fieldLabel("测试结果");
        card.addView(label);

        testInput = editText("识别结果会出现在这里");
        testInput.setMinLines(4);
        testInput.setGravity(Gravity.TOP | Gravity.START);
        testInput.setSingleLine(false);
        testInput.setCursorVisible(false);
        testInput.setFocusable(false);
        testInput.setFocusableInTouchMode(false);
        testInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        testInput.setShowSoftInputOnFocus(false);
        card.addView(testInput, fullWidthParams(dp(8)));

        testStatus = smallText("测试状态：待开始");
        card.addView(testStatus);

        testRecordButton = primaryButton("开始录音测试");
        testRecordButton.setOnClickListener(v -> toggleTestRecording());
        card.addView(testRecordButton, fullWidthParams(dp(8)));

        Button clear = secondaryButton("清空测试内容");
        clear.setOnClickListener(v -> {
            testInput.setText("");
            testStatus.setText("测试状态：已清空");
        });
        card.addView(clear, fullWidthParams(0));

        root.addView(card);
    }

    private void addModePanel(LinearLayout root) {
        LinearLayout card = panel(COLOR_CARD, COLOR_BORDER);
        card.addView(sectionTitle("识别模式"));

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, dp(2), 0, 0);

        localMode = modeRadio("本地离线", "默认使用 SenseVoice int8 本地模型");
        aliyunMode = modeRadio("阿里云 Paraformer", "填写 API Key 后走 DashScope 云端识别");
        group.addView(localMode);
        group.addView(aliyunMode);
        group.setOnCheckedChangeListener((ignored, checkedId) -> {
            AppPrefs.setMode(this, checkedId == aliyunMode.getId()
                    ? AppPrefs.MODE_ALIYUN
                    : AppPrefs.MODE_LOCAL);
            refresh();
        });
        card.addView(group);
        root.addView(card);
    }

    private void addCloudPanel(LinearLayout root) {
        LinearLayout card = panel(COLOR_CARD, COLOR_BORDER);
        card.addView(sectionTitle("阿里云配置"));

        card.addView(fieldLabel("API Key"));
        apiKeyInput = editText("DashScope API Key");
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setOnClickListener(v -> showKeyboard(apiKeyInput, false));
        card.addView(apiKeyInput, fullWidthParams(dp(10)));

        card.addView(fieldLabel("Workspace ID"));
        workspaceInput = editText("可选");
        workspaceInput.setSingleLine(true);
        workspaceInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        workspaceInput.setOnClickListener(v -> showKeyboard(workspaceInput, false));
        card.addView(workspaceInput, fullWidthParams(dp(12)));

        Button saveCloud = primaryButton("保存云端配置");
        saveCloud.setOnClickListener(v -> {
            AppPrefs.setAliyunApiKey(this, apiKeyInput.getText().toString());
            AppPrefs.setAliyunWorkspace(this, workspaceInput.getText().toString());
            Toast.makeText(this, "已保存云端配置", Toast.LENGTH_SHORT).show();
            refresh();
        });
        card.addView(saveCloud, fullWidthParams(0));

        root.addView(card);
    }

    private void addSystemPanel(LinearLayout root) {
        LinearLayout card = panel(COLOR_CARD, COLOR_BORDER);
        card.addView(sectionTitle("系统输入法"));

        currentImeStatus = statusBadge("");
        card.addView(statusRow("当前默认", currentImeStatus));

        LinearLayout row = actionRow();
        Button enableIme = secondaryButton("打开输入法设置");
        enableIme.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        Button switchIme = accentButton("切换输入法");
        switchIme.setOnClickListener(v -> showInputMethodPicker());
        row.addView(enableIme, weightedParams(1f, 0));
        row.addView(switchIme, weightedParams(1f, dp(8)));
        card.addView(row, fullWidthParams(0));

        root.addView(card);
    }

    private RadioButton modeRadio(String title, String detail) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(title + "\n" + detail);
        button.setTextSize(15);
        button.setTextColor(COLOR_TEXT);
        button.setButtonTintList(ColorStateList.valueOf(COLOR_PRIMARY));
        button.setMinHeight(dp(64));
        button.setPadding(0, dp(6), 0, dp(6));
        return button;
    }

    private LinearLayout panel(int fillColor, int strokeColor) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(roundedBackground(fillColor, strokeColor, dp(8), strokeColor == 0 ? 0 : dp(1)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        panel.setLayoutParams(params);
        return panel;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(COLOR_TEXT);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private TextView fieldLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(COLOR_MUTED);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private TextView smallText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(COLOR_MUTED);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private TextView statusBadge(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(dp(76));
        view.setMinHeight(dp(32));
        view.setPadding(dp(12), 0, dp(12), 0);
        return view;
    }

    private TextView chip(String text) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(13);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(Color.WHITE);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(dp(32));
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setBackground(roundedBackground(0x24ffffff, 0x33ffffff, dp(8), dp(1)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private LinearLayout statusRow(String label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(8));

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(15);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        return row;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private Button primaryButton(String text) {
        return styledButton(text, COLOR_PRIMARY, Color.WHITE, COLOR_PRIMARY);
    }

    private Button accentButton(String text) {
        return styledButton(text, COLOR_ACCENT, Color.WHITE, COLOR_ACCENT);
    }

    private Button secondaryButton(String text) {
        return styledButton(text, Color.WHITE, COLOR_TEXT, COLOR_BORDER);
    }

    private Button styledButton(String text, int fill, int textColor, int stroke) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(rippleBackground(fill, stroke));
        return button;
    }

    private EditText editText(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(15);
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(0xff94a3b8);
        input.setMinHeight(dp(52));
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(roundedBackground(Color.WHITE, COLOR_BORDER, dp(8), dp(1)));
        return input;
    }

    private LinearLayout.LayoutParams fullWidthParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, bottomMargin);
        return params;
    }

    private LinearLayout.LayoutParams weightedParams(float weight, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(leftMargin, 0, 0, 0);
        return params;
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
        GradientDrawable content = new GradientDrawable();
        content.setColor(fill);
        content.setCornerRadius(dp(8));
        content.setStroke(dp(1), stroke);
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), content, null);
    }

    private void refresh() {
        boolean hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        setBadge(permissionStatus, hasMic ? "已授权" : "未授权",
                hasMic ? COLOR_SOFT_GREEN : COLOR_SOFT_RED,
                hasMic ? 0xff047857 : COLOR_PRIMARY_DARK);
        permissionButton.setText(hasMic ? "麦克风权限已授权" : "授予麦克风权限");
        setButtonEnabledVisual(permissionButton, !hasMic);

        boolean modelReady = ModelManager.isModelReady(this);
        setBadge(modelStatus, modelReady ? "已就绪" : "未下载",
                modelReady ? COLOR_SOFT_GREEN : COLOR_SOFT_RED,
                modelReady ? 0xff047857 : COLOR_PRIMARY_DARK);
        downloadStatus.setText(modelReady
                ? "SenseVoice int8 本地模型已可用"
                : ModelManager.statusText(this));
        downloadButton.setText(modelReady ? "模型已就绪" : "下载本地模型");
        setButtonEnabledVisual(downloadButton, !modelReady);

        String currentMode = AppPrefs.getMode(this);
        if (AppPrefs.MODE_ALIYUN.equals(currentMode)) {
            aliyunMode.setChecked(true);
        } else {
            localMode.setChecked(true);
        }

        apiKeyInput.setText(AppPrefs.getAliyunApiKey(this));
        workspaceInput.setText(AppPrefs.getAliyunWorkspace(this));

        boolean selected = isHuniuImeSelected();
        setBadge(currentImeStatus, selected ? "虎妞已选中" : "未选中",
                selected ? COLOR_SOFT_GREEN : COLOR_SOFT_BLUE,
                selected ? 0xff047857 : COLOR_ACCENT);
    }

    private void setBadge(TextView view, String text, int fill, int textColor) {
        view.setText(text);
        view.setTextColor(textColor);
        view.setBackground(roundedBackground(fill, fill, dp(8), 0));
    }

    private void setButtonEnabledVisual(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.52f);
    }

    private void requestMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "麦克风权限已授权", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100);
    }

    private void startModelDownload() {
        downloadButton.setEnabled(false);
        downloadStatus.setText("准备下载...");
        executor.execute(() -> ModelManager.downloadModel(this, new ModelManager.DownloadListener() {
            @Override
            public void onProgress(String item, long downloaded, long total) {
                runOnUiThread(() -> {
                    if (total > 0) {
                        double percent = downloaded * 100.0 / total;
                        downloadStatus.setText(String.format(Locale.US, "%s %.1f%%", item, percent));
                    } else {
                        downloadStatus.setText(item + " " + (downloaded / 1024 / 1024) + " MB");
                    }
                });
            }

            @Override
            public void onDone() {
                runOnUiThread(() -> {
                    downloadStatus.setText("下载完成");
                    refresh();
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    downloadStatus.setText("下载失败：" + error.getMessage());
                    downloadButton.setEnabled(true);
                    refresh();
                });
            }
        }));
    }

    private void showKeyboard(View view, boolean forced) {
        view.requestFocus();
        view.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                int flags = forced ? InputMethodManager.SHOW_FORCED : InputMethodManager.SHOW_IMPLICIT;
                boolean shown = imm.showSoftInput(view, flags);
                if (!shown && forced && view.getWindowToken() != null) {
                    imm.toggleSoftInputFromWindow(view.getWindowToken(), InputMethodManager.SHOW_FORCED, 0);
                }
            }
        }, 120);
    }

    private void showInputMethodPicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showInputMethodPicker();
        }
    }

    private void toggleTestRecording() {
        if (testCapture.isRecording()) {
            stopTestAndRecognize();
        } else {
            startTestRecording();
        }
    }

    private void startTestRecording() {
        if (!isReadyForRecognition()) {
            return;
        }
        try {
            testCapture.start();
            testRecordButton.setText("停止并识别");
            testStatus.setText("测试状态：正在录音");
        } catch (Exception e) {
            testStatus.setText("测试状态：录音失败");
            Toast.makeText(this, "录音失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopTestAndRecognize() {
        testRecordButton.setEnabled(false);
        testStatus.setText("测试状态：正在识别");
        executor.execute(() -> {
            try {
                byte[] pcm = testCapture.stopAndGetPcm();
                String raw;
                if (AppPrefs.MODE_ALIYUN.equals(AppPrefs.getMode(this))) {
                    raw = AliyunRecognizer.recognize(this, pcm);
                } else {
                    raw = LocalSherpaRecognizer.get(this).recognizePcm16(pcm);
                }
                String clean = normalizeText(raw);
                runOnUiThread(() -> {
                    if (clean.isEmpty()) {
                        testStatus.setText("测试状态：没有识别到文字");
                    } else {
                        testInput.setText(clean);
                        testInput.setSelection(testInput.length());
                        testStatus.setText("测试状态：识别完成");
                    }
                    testRecordButton.setText("开始录音测试");
                    testRecordButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    testStatus.setText("测试状态：识别失败");
                    Toast.makeText(this, "识别失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    testRecordButton.setText("开始录音测试");
                    testRecordButton.setEnabled(true);
                });
            }
        });
    }

    private boolean isReadyForRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授予麦克风权限", Toast.LENGTH_SHORT).show();
            requestMicPermission();
            return false;
        }
        if (AppPrefs.MODE_LOCAL.equals(AppPrefs.getMode(this)) && !ModelManager.isModelReady(this)) {
            Toast.makeText(this, "请先下载本地模型", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (AppPrefs.MODE_ALIYUN.equals(AppPrefs.getMode(this)) && !AppPrefs.isAliyunReady(this)) {
            Toast.makeText(this, "请先填写阿里云 API Key", Toast.LENGTH_SHORT).show();
            showKeyboard(apiKeyInput, false);
            return false;
        }
        return true;
    }

    private boolean isHuniuImeSelected() {
        String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return HUNIU_IME_ID.equals(current);
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
