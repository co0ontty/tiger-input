# 虎妞输入法

虎妞输入法（huniu input）是一个极简安卓语音输入法。它只保留语音输入、删除、发送和收起键盘等必要操作，尽量少占屏幕空间，适合在聊天、终端、笔记和其他输入框里快速用语音输入文本。

## 功能

- 极简输入法面板：语音、删除、发送、收起键盘。
- 默认本地识别：使用 sherpa-onnx + SenseVoice 离线模型。
- 可选云端识别：支持填写阿里云 DashScope API Key，调用 Paraformer 实时识别。
- 实时输入反馈：录音中使用 composing text 显示中间识别结果，停止后用最终结果确认提交。
- 实时音量波形：波形根据麦克风采样的 RMS、峰值和语音变化实时波动。
- 系统 IME 行为：使用 Android `InputMethodService` 的标准输入视图和系统 inset 机制，避免覆盖宿主输入框。

## 项目结构

```text
app/src/main/java/com/codex/voiceinput/
  VoiceImeService.java       输入法服务和面板 UI
  AudioCapture.java          麦克风录音、音量和 PCM chunk 回调
  LocalSherpaRecognizer.java 本地 sherpa-onnx 离线识别
  AliyunRecognizer.java      阿里云 WebSocket 实时识别
  MainActivity.java          设置页和测试入口
  ModelManager.java          本地模型下载和状态检查
```

## 构建

需要 Android SDK，并安装 `platforms;android-36` 和 `build-tools;36.0.0`。

```bash
./gradlew assembleDebug --stacktrace
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 安装和启用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.codex.voiceinput android.permission.RECORD_AUDIO
adb shell ime enable com.codex.voiceinput/.VoiceImeService
adb shell ime set com.codex.voiceinput/.VoiceImeService
```

## 本地模型

应用默认使用本地识别。首次使用前在设置页下载 SenseVoice 模型，或将模型文件放到应用私有模型目录。模型由 `ModelManager` 管理：

- `model.int8.onnx`
- `tokens.txt`

## 云端识别

在设置页切换到阿里云模式并填写：

- DashScope API Key
- 可选 workspace

云端模式使用 WebSocket duplex streaming，把录音 PCM chunk 实时发送给阿里云，并把 partial result 写入当前输入框。

## Release APK

仓库包含 GitHub Actions workflow：`.github/workflows/release-apk.yml`。

推送 tag 后会自动：

1. 安装 Android 构建环境。
2. 运行 `./gradlew assembleDebug --stacktrace`。
3. 创建或更新 GitHub Release。
4. 上传 APK，文件名类似：

```text
huniu-input-v0.1.0.apk
```

发布一个新版本：

```bash
git tag v0.1.0
git push origin v0.1.0
```

## 说明

当前 release 上传的是 debug APK，因为项目还没有配置正式签名证书。后续如果要面向外部分发，可以补充 release keystore，并把 GitHub Actions 切换到 `assembleRelease`。
