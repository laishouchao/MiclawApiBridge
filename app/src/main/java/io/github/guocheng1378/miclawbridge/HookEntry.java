package io.github.guocheng1378.miclawbridge;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LibXposed API 102 模块入口 (v3.3 适配 com.miui.voiceassist)
 *
 * 关键发现: 核心类名被 ProGuard 混淆:
 *   com.xiaomi.ai.core.b = Channel (抽象类, 有 postEvent)
 *   com.xiaomi.ai.core.c = ChannelListener (抽象类, 有 onInstruction)
 *   com.xiaomi.ai.core.d = EventWrapper (Event + JSON)
 *   com.xiaomi.ai.core.e = InstructionWrapper (Instruction + JSON)
 *   com.xiaomi.ai.core.h = WSChannel (WebSocket 实现)
 *   com.xiaomi.ai.core.XMDChannel = XMD 实现
 *
 * API 类名未混淆:
 *   com.xiaomi.ai.api.common.Event<T> extends Message<EventHeader, T>
 *   com.xiaomi.ai.api.common.EventHeader extends MessageHeader (构造: EventHeader(namespace, name))
 *   com.xiaomi.ai.api.common.Message<H, P> (有 header, payload 字段, setHeader, setPayload)
 *   com.xiaomi.ai.api.Nlp$ExecuteQuery (有 query 字段, 构造: ExecuteQuery(String query))
 */
public class HookEntry extends XposedModule {

    private static final String TARGET_PACKAGE = "com.miui.voiceassist";

    // 混淆类名 (ProGuard)
    private static final String CLS_CHANNEL = "com.xiaomi.ai.core.b";
    private static final String CLS_CHANNEL_LISTENER = "com.xiaomi.ai.core.c";
    private static final String CLS_EVENT_WRAPPER = "com.xiaomi.ai.core.d";
    private static final String CLS_INSTRUCTION_WRAPPER = "com.xiaomi.ai.core.e";

    // API 类名 (未混淆)
    private static final String CLS_EVENT = "com.xiaomi.ai.api.common.Event";
    private static final String CLS_EVENT_HEADER = "com.xiaomi.ai.api.common.EventHeader";
    private static final String CLS_MESSAGE = "com.xiaomi.ai.api.common.Message";
    private static final String CLS_NLP = "com.xiaomi.ai.api.Nlp";
    private static final String CLS_EXECUTE_QUERY = "com.xiaomi.ai.api.Nlp$ExecuteQuery";

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
                        Logger.d("MiclawBridge v3.3 started (attach) - Channel approach");
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
                Logger.d("MiclawBridge v3.3 started (onPackageReady fallback)");
            }
        } catch (Throwable t) {
            Logger.e("HookEntry: onPackageReady fallback failed", t);
        }
    }

    /**
     * Hook Channel 通信层
     */
    private void hookChannelCommunication(ClassLoader classLoader) {
        // === 1. Hook Channel (com.xiaomi.ai.core.b) ===
        try {
            Class<?> channelClass = Class.forName(CLS_CHANNEL, false, classLoader);
            Logger.d("HookEntry: found Channel class: " + channelClass.getName());

            // Hook 构造函数, 捕获 Channel 实例
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

            // Hook postEvent(Event) - 捕获模板 + 日志诊断
            for (Method m : channelClass.getDeclaredMethods()) {
                if (m.getName().equals("postEvent") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    Class<?> paramType = m.getParameterTypes()[0];
                    Logger.d("HookEntry: found postEvent(" + paramType.getName() + ")");

                    // 只 hook postEvent(Event), 不 hook postEvent(d)
                    if (paramType.getName().equals(CLS_EVENT)) {
                        hook(m).intercept(chain -> {
                            Object event = chain.getArg(0);
                            if (event != null) {
                                AiClientHook.captureEventTemplate(event);
                            }
                            Object result = chain.proceed();
                            try {
                                Logger.d("HookEntry: postEvent(Event) result=" + result
                                    + " event=" + (event != null ? truncateStr(event.toString(), 300) : "null"));
                            } catch (Throwable ignored) {}
                            return result;
                        });
                        Logger.d("HookEntry: hooked postEvent(Event)");
                    }
                }
            }

            // 诊断: 列出 Channel 所有方法
            StringBuilder mList = new StringBuilder("Channel methods: ");
            for (Method m : channelClass.getDeclaredMethods()) {
                if (!m.getName().startsWith("access$")) {
                    mList.append(m.getName()).append("(").append(m.getParameterCount()).append(") ");
                }
            }
            Logger.d("HookEntry: " + mList.toString());

        } catch (ClassNotFoundException e) {
            Logger.d("HookEntry: Channel class not found: " + CLS_CHANNEL);
            // 回退: 搜索可能的其他类名
            findChannelClass(classLoader);
        } catch (Throwable t) {
            Logger.e("HookEntry: Channel hook failed: " + t.getMessage());
        }

        // === 2. Hook InstructionWrapper (com.xiaomi.ai.core.e) ===
        // e(Instruction<?>, String json) - 第二个参数是 JSON 字符串
        try {
            Class<?> iwClass = Class.forName(CLS_INSTRUCTION_WRAPPER, false, classLoader);
            Logger.d("HookEntry: found InstructionWrapper class: " + iwClass.getName());

            for (Constructor<?> ctor : iwClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                final int pc = ctor.getParameterCount();
                final Class<?>[] paramTypes = ctor.getParameterTypes();

                // 诊断
                StringBuilder sb = new StringBuilder("IW ctor(" + pc + "): ");
                for (Class<?> p : paramTypes) {
                    sb.append(p.getSimpleName()).append(", ");
                }
                Logger.d("HookEntry: " + sb.toString());

                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        // 找 String 类型的参数 (JSON)
                        for (int i = 0; i < pc; i++) {
                            Object arg = chain.getArg(i);
                            if (arg instanceof String) {
                                String json = (String) arg;
                                if (json.startsWith("{") && json.length() > 10) {
                                    Logger.d("HookEntry: InstructionWrapper JSON: " + truncateStr(json, 300));
                                    AiClientHook.onInstructionJson(json);
                                    break;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    return result;
                });
            }
            Logger.d("HookEntry: hooked InstructionWrapper constructors");
        } catch (ClassNotFoundException e) {
            Logger.d("HookEntry: InstructionWrapper not found: " + CLS_INSTRUCTION_WRAPPER);
        } catch (Throwable t) {
            Logger.e("HookEntry: InstructionWrapper hook failed: " + t.getMessage());
        }

        // === 3. Hook ChannelListener (com.xiaomi.ai.core.c) ===
        // onInstruction(b channel, e instructionWrapper)
        try {
            Class<?> listenerClass = Class.forName(CLS_CHANNEL_LISTENER, false, classLoader);
            Logger.d("HookEntry: found ChannelListener class: " + listenerClass.getName());

            for (Method m : listenerClass.getDeclaredMethods()) {
                if (m.getName().equals("onInstruction")) {
                    m.setAccessible(true);
                    final int pc = m.getParameterCount();
                    hook(m).intercept(chain -> {
                        try {
                            // 第二个参数是 InstructionWrapper (e)
                            if (pc >= 2) {
                                Object instrWrapper = chain.getArg(1);
                                if (instrWrapper != null) {
                                    // 尝试 getOriginal() 获取 JSON
                                    try {
                                        Method getOriginal = instrWrapper.getClass().getMethod("getOriginal");
                                        String json = (String) getOriginal.invoke(instrWrapper);
                                        if (json != null && json.startsWith("{")) {
                                            Logger.d("HookEntry: onInstruction JSON: " + truncateStr(json, 300));
                                            AiClientHook.onInstructionJson(json);
                                        }
                                    } catch (Exception ignored) {}
                                    // 也尝试 toString
                                    AiClientHook.onInstructionObject(instrWrapper);
                                }
                            }
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
                    Logger.d("HookEntry: hooked ChannelListener.onInstruction");
                }
            }
        } catch (ClassNotFoundException e) {
            Logger.d("HookEntry: ChannelListener not found: " + CLS_CHANNEL_LISTENER);
        } catch (Throwable t) {
            Logger.e("HookEntry: ChannelListener hook failed: " + t.getMessage());
        }

        // === 4. Hook Nlp$ExecuteQuery 构造函数 ===
        try {
            Class<?> eqClass = Class.forName(CLS_EXECUTE_QUERY, false, classLoader);
            Logger.d("HookEntry: found ExecuteQuery class: " + eqClass.getName());

            // 诊断: 打印字段
            for (Field f : eqClass.getDeclaredFields()) {
                if (!f.getName().startsWith("access$") && !f.getName().startsWith("$")) {
                    Logger.d("HookEntry: ExecuteQuery field: " + f.getName() + " (" + f.getType().getSimpleName() + ")");
                }
            }

            for (Constructor<?> ctor : eqClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                final int pc = ctor.getParameterCount();
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Logger.d("HookEntry: ExecuteQuery created (ctor " + pc + " args)");
                        if (pc >= 1) {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof String) {
                                Logger.d("HookEntry: ExecuteQuery query=" + truncateStr((String) arg0, 100));
                            }
                        }
                        AiClientHook.captureExecuteQuery(chain.getThisObject());
                    } catch (Throwable ignored) {}
                    return result;
                });
            }
            Logger.d("HookEntry: hooked ExecuteQuery constructors");
        } catch (ClassNotFoundException e) {
            Logger.d("HookEntry: ExecuteQuery not found: " + CLS_EXECUTE_QUERY);
        }

        // === 5. 诊断: 打印 Event 和 EventHeader 类信息 ===
        try {
            Class<?> eventClass = Class.forName(CLS_EVENT, false, classLoader);
            StringBuilder eFields = new StringBuilder("Event fields: ");
            for (Field f : eventClass.getDeclaredFields()) {
                if (!f.getName().startsWith("access$") && !f.getName().startsWith("$")) {
                    eFields.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
                }
            }
            Logger.d("HookEntry: " + eFields.toString());

            // 也打印 Message 的字段 (header, payload)
            Class<?> messageClass = Class.forName(CLS_MESSAGE, false, classLoader);
            StringBuilder mFields = new StringBuilder("Message fields: ");
            for (Field f : messageClass.getDeclaredFields()) {
                mFields.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
            }
            Logger.d("HookEntry: " + mFields.toString());

            // 打印 Event 构造函数
            for (Constructor<?> ctor : eventClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                StringBuilder sb = new StringBuilder("Event ctor: (");
                for (Class<?> p : ctor.getParameterTypes()) {
                    sb.append(p.getSimpleName()).append(", ");
                }
                sb.append(")");
                Logger.d("HookEntry: " + sb.toString());
            }
        } catch (Exception e) {
            Logger.d("HookEntry: Event diagnosis failed: " + e.getMessage());
        }

        try {
            Class<?> ehClass = Class.forName(CLS_EVENT_HEADER, false, classLoader);
            StringBuilder ehFields = new StringBuilder("EventHeader fields: ");
            for (Field f : ehClass.getDeclaredFields()) {
                if (!f.getName().startsWith("access$") && !f.getName().startsWith("$")) {
                    ehFields.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
                }
            }
            Logger.d("HookEntry: " + ehFields.toString());

            // 打印 EventHeader 构造函数
            for (Constructor<?> ctor : ehClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                StringBuilder sb = new StringBuilder("EventHeader ctor: (");
                for (Class<?> p : ctor.getParameterTypes()) {
                    sb.append(p.getSimpleName()).append(", ");
                }
                sb.append(")");
                Logger.d("HookEntry: " + sb.toString());
            }
        } catch (Exception e) {
            Logger.d("HookEntry: EventHeader diagnosis failed: " + e.getMessage());
        }
    }

    /**
     * 回退: 搜索 Channel 类 (可能类名不同)
     */
    private void findChannelClass(ClassLoader classLoader) {
        try {
            // 搜索 com.xiaomi.ai.core 包下所有类
            for (char c = 'a'; c <= 'z'; c++) {
                try {
                    Class<?> cls = Class.forName("com.xiaomi.ai.core." + c, false, classLoader);
                    // 检查是否有 postEvent 方法
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("postEvent")) {
                            Logger.d("HookEntry: found postEvent on class: " + cls.getName());
                        }
                    }
                } catch (ClassNotFoundException ignored) {}
            }
            // 也检查 XMDChannel
            try {
                Class<?> cls = Class.forName("com.xiaomi.ai.core.XMDChannel", false, classLoader);
                Logger.d("HookEntry: found XMDChannel: " + cls.getName());
            } catch (ClassNotFoundException ignored) {}
        } catch (Exception e) {
            Logger.d("HookEntry: findChannelClass failed: " + e.getMessage());
        }
    }

    private static String truncateStr(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
