package io.github.guocheng1378.miclawbridge;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LibXposed API 102 模块入口 (v3.1 适配 com.miui.voiceassist)
 *
 * Channel 方案:
 *  1. Hook com.xiaomi.ai.core.Channel 捕获通信通道实例
 *  2. Hook InstructionWrapper 构造函数, 解析 JSON payload 提取 AI 回复文本
 *  3. 通过 Channel.postEvent() 发送 Nlp$ExecuteQuery 文本查询
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
                        hookChannelCommunication(param.getDefaultClassLoader());
                        BridgeStarter.start(ctx);
                        Logger.d("MiclawBridge v3.1 started (attach) - Channel approach");
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
            hookChannelCommunication(param.getClassLoader());
            Class<?> atClass = Class.forName("android.app.ActivityThread", true, param.getClassLoader());
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            Context ctx = (Context) currentApp.invoke(null);
            if (ctx != null) {
                BridgeStarter.start(ctx);
                Logger.d("MiclawBridge v3.1 started (onPackageReady fallback)");
            }
        } catch (Throwable t) {
            Logger.e("HookEntry: onPackageReady fallback failed", t);
        }
    }

    /**
     * Hook Channel 通信层 + InstructionWrapper 拦截
     */
    private void hookChannelCommunication(ClassLoader classLoader) {
        // === 1. Channel 类: 捕获实例 ===
        try {
            Class<?> channelClass = Class.forName("com.xiaomi.ai.core.Channel", false, classLoader);
            // Hook 所有构造函数, 捕获 Channel 实例
            for (Constructor<?> ctor : channelClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        AiClientHook.setChannel(chain.getThisObject());
                        Logger.d("HookEntry: Channel CREATED: " + chain.getThisObject().getClass().getName());
                    } catch (Throwable t) {
                        Logger.e("HookEntry: Channel ctor hook error: " + t.getMessage());
                    }
                    return result;
                });
            }
            Logger.d("HookEntry: hooked Channel constructors");

            // Hook getListener 捕获 listener 实例
            try {
                Method getListener = channelClass.getDeclaredMethod("getListener");
                getListener.setAccessible(true);
                hook(getListener).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (result != null) {
                            AiClientHook.setChannelListener(result);
                        }
                    } catch (Throwable ignored) {}
                    return result;
                });
            } catch (Exception e) {
                Logger.d("HookEntry: getListener hook failed: " + e.getMessage());
            }
        } catch (ClassNotFoundException e) {
            Logger.d("HookEntry: Channel class not found");
        } catch (Throwable t) {
            Logger.e("HookEntry: Channel hook failed: " + t.getMessage());
        }

        // === 2. InstructionWrapper: 核心 - 解析 AI 回复 ===
        try {
            Class<?> iwClass = Class.forName("com.xiaomi.ai.core.InstructionWrapper", false, classLoader);
            for (Constructor<?> ctor : iwClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                final int pc = ctor.getParameterCount();
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        // 提取第一个参数的 JSON 字符串
                        String json = null;
                        if (pc >= 1) {
                            Object arg0 = chain.getArg(0);
                            if (arg0 != null) {
                                json = arg0.toString();
                            }
                        }
                        if (json != null && json.startsWith("{")) {
                            AiClientHook.onInstructionJson(json);
                        }
                    } catch (Throwable ignored) {}
                    return result;
                });
            }
            Logger.d("HookEntry: hooked InstructionWrapper constructors");
        } catch (Exception e) {
            Logger.d("HookEntry: InstructionWrapper not found");
        }

        // === 3. ChannelListener: Hook onInstruction 回调 ===
        try {
            Class<?> listenerClass = Class.forName("com.xiaomi.ai.core.ChannelListener", false, classLoader);
            for (Method m : listenerClass.getDeclaredMethods()) {
                if (m.getName().equals("onInstruction")) {
                    m.setAccessible(true);
                    final int pc = m.getParameterCount();
                    hook(m).intercept(chain -> {
                        try {
                            if (pc >= 2) {
                                Object instruction = chain.getArg(1);
                                if (instruction != null) {
                                    AiClientHook.onInstructionObject(instruction);
                                }
                            }
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
                    Logger.d("HookEntry: hooked ChannelListener.onInstruction");
                }
            }
        } catch (Exception e) {
            Logger.d("HookEntry: ChannelListener not found");
        }

        // === 4. NLP 事件类: 仅用于日志 ===
        String[] nlpClasses = {
            "com.xiaomi.ai.api.Nlp$AnswerResult",
            "com.xiaomi.ai.api.Nlp$StartStream",
            "com.xiaomi.ai.api.Nlp$FinishStream",
            "com.xiaomi.ai.api.Nlp$StartAnswer",
            "com.xiaomi.ai.api.Nlp$FinishAnswer",
            "com.xiaomi.ai.api.Nlp$ExecuteQuery",
        };
        for (String className : nlpClasses) {
            try {
                Class<?> cls = Class.forName(className, false, classLoader);
                for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                    ctor.setAccessible(true);
                    final String cn = className;
                    hook(ctor).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            String simpleName = cn.substring(cn.lastIndexOf('$') + 1);
                            AiClientHook.onNlpMarker(simpleName);
                        } catch (Throwable ignored) {}
                        return result;
                    });
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
    }
}
