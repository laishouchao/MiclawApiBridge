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
                        // attach 阶段 getApplicationContext() 为 null,
                        // 直接传 ctx (即 Application 自身), BridgeStarter 内部做容错
                        BridgeStarter.start(ctx);
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
                BridgeStarter.start(ctx);
                Logger.d("MiclawBridge v2.2 started (onPackageReady fallback)");
            }
        } catch (Throwable t) {
            Logger.e("HookEntry: onPackageReady fallback failed", t);
        }
    }

    /**
     * Hook handleInstruction 拦截AI响应 + 构造函数捕获实例
     * 沿继承链查找: AbsAiClient -> AiClient
     */
    private void hookHandleInstruction(ClassLoader classLoader) {
        try {
            // === 核心: Hook 构造函数, AiClient 创建时立即捕获 ===
            String[] aiClientClasses = {
                "com.xiaomi.ai.conn.basic.AbsAiClient",
                "com.xiaomi.ai.conn.basic.AiClient"
            };
            for (String className : aiClientClasses) {
                try {
                    Class<?> cls = Class.forName(className, false, classLoader);
                    // Hook 构造函数
                    try {
                        java.lang.reflect.Constructor<?>[] ctors = cls.getDeclaredConstructors();
                        for (java.lang.reflect.Constructor<?> ctor : ctors) {
                            ctor.setAccessible(true);
                            hook(ctor).intercept(chain -> {
                                Object result = chain.proceed();
                                try {
                                    AiClientHook.setAiClient(chain.getThisObject());
                                    Logger.d("HookEntry: AiClient CREATED: " + chain.getThisObject().getClass().getName());
                                } catch (Throwable t) {
                                    Logger.e("ctor hook error: " + t.getMessage());
                                }
                                return result;
                            });
                            Logger.d("HookEntry: hooked " + className + " constructor(" + ctor.getParameterCount() + " params)");
                        }
                    } catch (Throwable t) {
                        Logger.e("HookEntry: ctor hook failed for " + className + ": " + t.getMessage());
                    }

                    // 列举所有方法 (诊断用)
                    StringBuilder sb = new StringBuilder();
                    for (Method m : cls.getDeclaredMethods()) {
                        sb.append(m.getName()).append("(").append(m.getParameterCount()).append(") ");
                    }
                    Logger.d("HookEntry: " + className + " methods: " + sb.toString());

                    // 列举 start() 方法参数类型
                    for (Method m : cls.getDeclaredMethods()) {
                        if ("start".equals(m.getName())) {
                            StringBuilder paramInfo = new StringBuilder("start(");
                            for (Class<?> p : m.getParameterTypes()) {
                                paramInfo.append(p.getName()).append(", ");
                            }
                            paramInfo.append(")");
                            Logger.d("HookEntry: " + className + "." + paramInfo.toString());
                        }
                    }

                    // 列举构造函数参数类型
                    for (java.lang.reflect.Constructor<?> ctor : cls.getDeclaredConstructors()) {
                        StringBuilder ctorInfo = new StringBuilder("ctor(");
                        for (Class<?> p : ctor.getParameterTypes()) {
                            ctorInfo.append(p.getName()).append(", ");
                        }
                        ctorInfo.append(")");
                        Logger.d("HookEntry: " + className + " " + ctorInfo.toString());
                    }

                    // Hook handleInstruction
                    for (Method m : cls.getDeclaredMethods()) {
                        if ("handleInstruction".equals(m.getName())) {
                            m.setAccessible(true);
                            hook(m).intercept(chain -> {
                                try {
                                    AiClientHook.setAiClient(chain.getThisObject());
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
                    Logger.d("HookEntry: class not found: " + className);
                }
            }

            // 也尝试 Hook processInstruction
            try {
                Class<?> aiClass = Class.forName("com.xiaomi.ai.conn.basic.AbsAiClient", false, classLoader);
                for (Method m : aiClass.getDeclaredMethods()) {
                    String name = m.getName();
                    if ("processInstruction".equals(name)) {
                        m.setAccessible(true);
                        hook(m).intercept(chain -> {
                            try {
                                AiClientHook.setAiClient(chain.getThisObject());
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
