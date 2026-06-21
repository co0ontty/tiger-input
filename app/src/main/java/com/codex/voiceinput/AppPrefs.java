package com.codex.voiceinput;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    static final String MODE_LOCAL = "local";
    static final String MODE_ALIYUN = "aliyun";
    static final String ALIYUN_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

    private static final String PREFS = "voice_input_settings";
    private static final String KEY_MODE = "mode";
    private static final String KEY_ALIYUN_API_KEY = "aliyun_api_key";
    private static final String KEY_ALIYUN_WORKSPACE = "aliyun_workspace";

    private AppPrefs() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getMode(Context context) {
        return prefs(context).getString(KEY_MODE, MODE_LOCAL);
    }

    static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, mode).apply();
    }

    static String getAliyunApiKey(Context context) {
        return prefs(context).getString(KEY_ALIYUN_API_KEY, "");
    }

    static void setAliyunApiKey(Context context, String apiKey) {
        prefs(context).edit().putString(KEY_ALIYUN_API_KEY, apiKey == null ? "" : apiKey.trim()).apply();
    }

    static String getAliyunWorkspace(Context context) {
        return prefs(context).getString(KEY_ALIYUN_WORKSPACE, "");
    }

    static void setAliyunWorkspace(Context context, String workspace) {
        prefs(context).edit().putString(KEY_ALIYUN_WORKSPACE, workspace == null ? "" : workspace.trim()).apply();
    }

    static boolean isAliyunReady(Context context) {
        return !getAliyunApiKey(context).isEmpty();
    }
}
