package io.github.guocheng1378.miclawbridge;

import android.content.Context;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LibXposed API 102 模块入口 (v2.2 适配 com.miui.voiceassist)
 *
 * 双保险启动:
 *  1. onPackageLoaded: hook Application.attach, attach 时立即启动 Bridge
 *  2. onPackageReady: 兜底, attach hook 可能错过时反射 ActivityThread.currentApplication 启动
 *  3. Hook handleInstruction() 拦截AI响应
 * 排除 system_server 和模块自身进程, 防止系统崩溃 / UI 闪退
 */
public class HookEntry extends XposedModule {

    private static final String TARGET_PACKAGE = "com.miui.voiceassist";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer()) {
            Logger.d("HookEntry: system_server, skip");
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        Logger.d("HookEntry: onSystemServerStarting, skip");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        try {
            Class<?> appClass = Class.forName("android.app.Application", true, param.getDefaultClassLoader());
            Method attach = appClass.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Context ctx = (Context) chain.getArg(0);
                    if (ctx != null) {
                        // Hook handleInstruction 拦截AI响应
                        hookHandleInstruction(param.getDefaultClassLoader());
                        BridgeStarter.start(ctx.getApplicationContext());
                        Logger.d("MiclawBridge v2.2 started (attach) - adapted for voiceassist");
                    }
                } catch (Throwable t) {
                    Logger.e("HookEntry: start via attach failed", t);
                }
                return result;
            });
            Logger.d("HookEntry: hooked Application.attach (onPackageLoaded)");
        } catch (Throwable t) {
            Logger.e("HookEntry: onPackageLoaded hook attach failed", t);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        try {
            // Hook handleInstruction
            hookHandleInstruction(param.getClassLoader());

            Class<?> atClass = Class.forName("android.app.ActivityThread", true, param.getClassLoader());
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            Context ctx = (Context) currentApp.invoke(null);
            if (ctx != null) {
                BridgeStarter.start(ctx.getApplicationContext());
                Logger.d("MiclawBridge v2.2 started (onPackageReady fallback)");
            }
        } catch (Throwable t) {
            Logger.e("HookEntry: onPackageReady fallback failed", t);
        }
    }

    /**
     * Hook handleInstruction 拦截AI响应
     * 沿继承链查找: AbsAiClient -> AiClient
     */
    private void hookHandleInstruction(ClassLoader classLoader) {
        try {
            // 尝试 Hook AbsAiClient.handleInstruction
            String[] classNames = {
                "com.xiaomi.ai.conn.basic.AbsAiClient",
                "com.xiaomi.ai.conn.basic.AiClient"
            };

            for (String className : classNames) {
                try {
                    Class<?> cls = Class.forName(className, false, classLoader);
                    for (Method m : cls.getDeclaredMethods()) {
                        if ("handleInstruction".equals(m.getName())) {
                            m.setAccessible(true);
                            hook(m).intercept(chain -> {
                                try {
                                    // 尝试从参数中提取响应文本
                                    Object instruction = chain.getArg(0);
                                    String text = extractTextFromInstruction(instruction);
                                    if (text != null && !text.isEmpty()) {
                                        AiClientHook.onResponse(text, false);
                                    }
                                } catch (Throwable t) {
                                    Logger.e("handleInstruction hook error: " + t.getMessage());
                                }
                                return chain.proceed();
                            });
                            Logger.d("HookEntry: hooked " + className + ".handleInstruction");
                        }
                    }
                } catch (ClassNotFoundException e) {
                    // 继续尝试下一个
                }
            }

            // 也尝试 Hook onResponse / processInstruction 相关方法
            try {
                Class<?> aiClass = Class.forName("com.xiaomi.ai.conn.basic.AbsAiClient", false, classLoader);
                for (Method m : aiClass.getDeclaredMethods()) {
                    String name = m.getName();
                    if ("processInstruction".equals(name)) {
                        m.setAccessible(true);
                        hook(m).intercept(chain -> {
                            try {
                                Object instruction = chain.getArg(0);
                                String text = extractTextFromInstruction(instruction);
                                if (text != null && !text.isEmpty()) {
                                    AiClientHook.onResponse(text, false);
                                }
                            } catch (Throwable t) {}
                            return chain.proceed();
                        });
                        Logger.d("HookEntry: hooked processInstruction");
                    }
                }
            } catch (Exception e) {
                Logger.e("hookHandleInstruction extra: " + e.getMessage());
            }

        } catch (Throwable t) {
            Logger.e("hookHandleInstruction failed: " + t.getMessage());
        }
    }

    /**
     * 从 Instruction 对象中提取文本响应
     */
    private String extractTextFromInstruction(Object instruction) {
        if (instruction == null) return null;
        try {
            Class<?> cls = instruction.getClass();

            // 尝试 getText() / getReply() / getContent()
            String[] getters = {"getText", "getReply", "getContent", "toString"};
            for (String getter : getters) {
                try {
                    Method m = cls.getMethod(getter);
                    m.setAccessible(true);
                    Object val = m.invoke(instruction);
                    if (val instanceof String && !((String) val).isEmpty()) {
                        return (String) val;
                    }
                } catch (NoSuchMethodException ignored) {}
            }

            // 尝试直接读取字段
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == String.class) {
                    String val = (String) f.get(instruction);
                    if (val != null && val.length() > 5) {
                        return val;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
