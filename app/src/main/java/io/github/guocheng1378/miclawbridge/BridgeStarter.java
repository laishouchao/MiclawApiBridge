package io.github.guocheng1378.miclawbridge;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

/** 统一启动器: LibXposed 和老 Xposed 双入口共享, 防重复启动 */
public class BridgeStarter {
    private static final AtomicBoolean started = new AtomicBoolean(false);

    public static void start(Context context) {
        if (!started.compareAndSet(false, true)) {
            Logger.d("BridgeStarter: already started, skip");
            return;
        }
        try {
            // Application.attach() 阶段 getApplicationContext() 可能为 null,
            // 此时直接使用 attach 传入的 Context (即 Application 自身)
            Context appCtx = context.getApplicationContext();
            if (appCtx == null) appCtx = context;
            Config.loadFrom(appCtx);
            HttpServer server = new HttpServer(appCtx);
            server.start();
            Logger.d("Miclaw API Bridge started (v3.1 voiceassist-channel)");
        } catch (Throwable t) {
            Logger.e("Bridge start failed", t);
            started.set(false);
        }
    }
}
