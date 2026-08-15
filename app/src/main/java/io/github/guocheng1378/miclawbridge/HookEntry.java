package io.github.guocheng1378.miclawbridge;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LibXposed API 102 模块入口 (v3.0 适配 com.miui.voiceassist)
 *
 * Channel 方案:
 *  1. Hook com.xiaomi.ai.core.Channel 捕获通信通道实例
 *  2. Hook NLP 响应类拦截 AI 回复
 *  3. 通过 Channel 发送文本查询
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
                        Logger.d("MiclawBridge v3.0 started (attach) - Channel approach");
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
                Logger.d("MiclawBridge v3.0 started (onPackageReady fallback)");
            }
        } catch (Throwable t) {
            Logger.e("HookEntry: onPackageReady fallback failed", t);
        }
    }

    /**
     * Hook Channel 通信层 + NLP 响应拦截
     */
    private void hookChannelCommunication(ClassLoader classLoader) {
        // === 1. Channel 类: 捕获实例 + Hook 通信方法 ===
        try {
            Class<?> channelClass = Class.forName("com.xiaomi.ai.core.Channel", false, classLoader);
            logMethods(channelClass);
            logConstructors(channelClass);

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

            // Hook 关键方法 (send/receive/event/instruction)
            for (Method m : channelClass.getDeclaredMethods()) {
                String name = m.getName().toLowerCase();
                if (name.contains("send") || name.contains("event") || name.contains("instruction")
                    || name.contains("receive") || name.contains("dispatch") || name.contains("handle")
                    || name.contains("notify") || name.contains("callback") || name.contains("listener")) {
                    m.setAccessible(true);
                    final int argCount = m.getParameterCount();
                    hook(m).intercept(chain -> {
                        try {
                            StringBuilder args = new StringBuilder();
                            for (int i = 0; i < argCount; i++) {
                                Object arg = chain.getArg(i);
                                String argStr = arg == null ? "null" : arg.getClass().getSimpleName() + ":" + truncate(arg.toString(), 100);
                                args.append("[").append(i).append("=").append(argStr).append("] ");
                            }
                            Logger.d("HookEntry: Channel." + m.getName() + "(" + args + ")");
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
                }
            }
        } catch (ClassNotFoundException e) {
            Logger.d("HookEntry: Channel class not found");
        } catch (Throwable t) {
            Logger.e("HookEntry: Channel hook failed: " + t.getMessage());
        }

        // === 2. ChannelListener 接口: 了解回调方法 ===
        try {
            Class<?> listenerClass = Class.forName("com.xiaomi.ai.core.ChannelListener", false, classLoader);
            logMethods(listenerClass);
        } catch (Exception e) {
            Logger.d("HookEntry: ChannelListener not found");
        }

        // === 3. SpeechRecognizer$PostBack: 文本输入入口 ===
        try {
            Class<?> postBackClass = Class.forName("com.xiaomi.ai.api.SpeechRecognizer$PostBack", false, classLoader);
            logMethods(postBackClass);
            logConstructors(postBackClass);
            for (Constructor<?> ctor : postBackClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        int pc = ctor.getParameterCount();
                        StringBuilder args = new StringBuilder();
                        for (int i = 0; i < pc; i++) {
                            Object arg = chain.getArg(i);
                            args.append("[").append(i).append("=").append(arg == null ? "null" : truncate(arg.toString(), 200)).append("] ");
                        }
                        Logger.d("HookEntry: PostBack CREATED: " + args);
                        AiClientHook.onPostBackCreated(chain.getThisObject());
                    } catch (Throwable ignored) {}
                    return result;
                });
            }
        } catch (Exception e) {
            Logger.d("HookEntry: SpeechRecognizer$PostBack not found");
        }

        // === 4. NLP 响应类: 拦截 AI 回复 ===
        String[] nlpClasses = {
            "com.xiaomi.ai.api.Nlp$AnswerResult",
            "com.xiaomi.ai.api.Nlp$StartStream",
            "com.xiaomi.ai.api.Nlp$FinishStream",
            "com.xiaomi.ai.api.Nlp$StartAnswer",
            "com.xiaomi.ai.api.Nlp$FinishAnswer",
            "com.xiaomi.ai.api.Nlp$LargeLanguageModelContent",
            "com.xiaomi.ai.api.Nlp$ExecuteQuery",
            "com.xiaomi.ai.api.Nlp$Request",
        };
        for (String className : nlpClasses) {
            try {
                Class<?> cls = Class.forName(className, false, classLoader);
                logMethods(cls);
                logConstructors(cls);
                // Hook 构造函数, 拦截响应创建
                for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                    ctor.setAccessible(true);
                    final int pc = ctor.getParameterCount();
                    final String cn = className;
                    hook(ctor).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            StringBuilder args = new StringBuilder();
                            for (int i = 0; i < pc; i++) {
                                Object arg = chain.getArg(i);
                                args.append("[").append(i).append("=").append(arg == null ? "null" : truncate(arg.toString(), 200)).append("] ");
                            }
                            Logger.d("HookEntry: " + cn + " CREATED: " + args);
                            AiClientHook.onNlpEvent(cn, chain.getThisObject());
                        } catch (Throwable ignored) {}
                        return result;
                    });
                }
            } catch (ClassNotFoundException e) {
                Logger.d("HookEntry: " + className + " not found");
            }
        }

        // === 5. InstructionWrapper / EventWrapper ===
        String[] wrapperClasses = {
            "com.xiaomi.ai.core.InstructionWrapper",
            "com.xiaomi.ai.core.EventWrapper",
        };
        for (String className : wrapperClasses) {
            try {
                Class<?> cls = Class.forName(className, false, classLoader);
                logMethods(cls);
                logConstructors(cls);
                for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                    ctor.setAccessible(true);
                    final int pc = ctor.getParameterCount();
                    final String cn = className;
                    hook(ctor).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            StringBuilder args = new StringBuilder();
                            for (int i = 0; i < pc; i++) {
                                Object arg = chain.getArg(i);
                                args.append("[").append(i).append("=").append(arg == null ? "null" : truncate(arg.toString(), 200)).append("] ");
                            }
                            Logger.d("HookEntry: " + cn + " CREATED: " + args);
                        } catch (Throwable ignored) {}
                        return result;
                    });
                }
            } catch (ClassNotFoundException e) {
                Logger.d("HookEntry: " + className + " not found");
            }
        }

        // === 6. 尝试 Hook AssistInteractionService (可能持有 Channel) ===
        try {
            Class<?> svcClass = Class.forName("com.xiaomi.voiceassistant.AssistInteractionService", false, classLoader);
            logMethods(svcClass);
        } catch (Exception e) {
            Logger.d("HookEntry: AssistInteractionService not found");
        }
    }

    private void logMethods(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        sb.append(cls.getName()).append(" methods: ");
        for (Method m : cls.getDeclaredMethods()) {
            sb.append(m.getName()).append("(").append(m.getParameterCount()).append(") ");
        }
        Logger.d("HookEntry: " + sb.toString());
    }

    private void logConstructors(Class<?> cls) {
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            StringBuilder sb = new StringBuilder("ctor(");
            for (Class<?> p : ctor.getParameterTypes()) {
                sb.append(p.getName()).append(", ");
            }
            sb.append(")");
            Logger.d("HookEntry: " + cls.getName() + " " + sb.toString());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
