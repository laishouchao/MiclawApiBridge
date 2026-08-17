package io.github.guocheng1378.xiaoaibridge;

import android.util.Log;

public class Logger {
    private static final String TAG = "XiaoAiBridge";

    public static void d(String msg) {
        Log.d(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
    }
}
