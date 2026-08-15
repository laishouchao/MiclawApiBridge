package io.github.guocheng1378.miclawbridge;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * LibXposed API 102 模块入口 (v3.4 适配 com.miui.voiceassist)
 *
 * 关键发现 (jadx 反编译分析):
 *
 * 1. 类名混淆映射:
 *    com.xiaomi.ai.core.b = Channel (抽象类, postEvent(Event) + postEvent(d))
 *    com.xiaomi.ai.core.c = ChannelListener (抽象类, onInstruction(b, e))
 *    com.xiaomi.ai.core.d = EventWrapper (Event + JSON)
 *    com.xiaomi.ai.core.e = InstructionWrapper (Instruction + JSON)
 *    com.xiaomi.ai.core.h = WSChannel (WebSocket 实现, extends b)
 *
 * 2. API 类名未混淆:
 *    com.xiaomi.ai.api.common.Event<T> extends Message<EventHeader, T>
 *    com.xiaomi.ai.api.common.EventHeader extends MessageHeader
 *    com.xiaomi.ai.api.common.APIUtils.buildEvent(EventPayload) -> Event
 *    com.xiaomi.ai.api.Nlp$Request implements EventPayload (有 query 字段, @Required)
 *    com.xiaomi.ai.api.Nlp$ExecuteQuery implements InstructionPayload (不能作为 Event 发送!)
 *
 * 3. 发送流程 (来自 jadx 代码分析):
 *    voice assistant: Nlp.Request(text) -> APIUtils.buildEvent(request) -> channel.postEvent(event)
 *    channel.postEvent(Event): event.toJsonString() -> new EventWrapper(event, json) -> postEvent(wrapper)
 *    WSChannel.postEvent(wrapper): 检查 f28821b != null && f28821b.e() (已连接) -> 发送
 *
 * 4. 接收流程:
 *    WebSocket 收到消息 -> ChannelListener.onInstruction(b, e) -> e.getOriginal() = JSON
 *    JSON 中 SpeechSynthesizer.Speak/SpeakStream 的 payload.text = 回复文本
 */
public class HookEntry extends XposedModule {

    private static final String TARGET_PACKAGE = "com.miui.voiceassist";

    // 混淆类名候选 (运行时真实名 + jadx 反混淆名)
    private static final String[] CLS_CHANNEL_NAMES = {
        "com.xiaomi.ai.core.b",           // jadx 反混淆名 (运行时存在)
        "com.xiaomi.ai.core.Channel"       // 可能的原始名
    };
    private static final String[] CLS_CHANNEL_LISTENER_NAMES = {
        "com.xiaomi.ai.core.c",           // jadx 反混淆名 (运行时存在)
        "com.xiaomi.ai.core.ChannelListener"
    };
    private static final String[] CLS_INSTRUCTION_WRAPPER_NAMES = {
        "com.xiaomi.ai.core.e",           // jadx 反混淆名 (运行时存在)
        "com.xiaomi.ai.core.InstructionWrapper"
    };
    private static final String[] CLS_EVENT_WRAPPER_NAMES = {
        "com.xiaomi.ai.core.d",
        "com.xiaomi.ai.core.EventWrapper"
    };

    // API 类名 (未混淆)
    private static final String CLS_EVENT = "com.xiaomi.ai.api.common.Event";
    private static final String CLS_EVENT_HEADER = "com.xiaomi.ai.api.common.EventHeader";
    private static final String CLS_MESSAGE = "com.xiaomi.ai.api.common.Message";
    private static final String CLS_API_UTILS = "com.xiaomi.ai.api.common.APIUtils";
    private static final String CLS_EVENT_PAYLOAD = "com.xiaomi.ai.api.common.EventPayload";
    private static final String CLS_NLP_REQUEST = "com.xiaomi.ai.api.Nlp$Request";
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
                        Logger.d("MiclawBridge v3.5 started (attach) - Channel+Request approach");
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
                Logger.d("MiclawBridge v3.5 started (onPackageReady fallback)");
            }
        } catch (Throwable t) {
            Logger.e("HookEntry: onPackageReady fallback failed", t);
        }
    }

    /** 尝试多个类名, 返回第一个能加载的 Class */
    private Class<?> resolveClass(ClassLoader cl, String[] names) {
        for (String name : names) {
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    /**
     * Hook Channel 通信层
     */
    private void hookChannelCommunication(ClassLoader classLoader) {
        hookChannel(classLoader);
        hookInstructionWrapper(classLoader);
        hookChannelListener(classLoader);
        hookNlpRequest(classLoader);
        hookApiUtils(classLoader);
        diagnoseEventClasses(classLoader);
    }

    // === 1. Hook Channel (com.xiaomi.ai.core.b) ===
    private void hookChannel(ClassLoader classLoader) {
        Class<?> channelClass = resolveClass(classLoader, CLS_CHANNEL_NAMES);
        if (channelClass == null) {
            Logger.d("HookEntry: Channel class not found in candidates: "
                + String.join(", ", CLS_CHANNEL_NAMES));
            findChannelClass(classLoader);
            return;
        }
        Logger.d("HookEntry: found Channel class: " + channelClass.getName());

        // Hook 构造函数, 捕获 Channel 实例
        for (Constructor<?> ctor : channelClass.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            hook(ctor).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object channel = chain.getThisObject();
                    AiClientHook.setChannel(channel);
                    Logger.d("HookEntry: Channel CREATED: " + channel.getClass().getName());
                } catch (Throwable t) {
                    Logger.e("HookEntry: Channel ctor hook error: " + t.getMessage());
                }
                return result;
            });
        }
        Logger.d("HookEntry: hooked Channel constructors");

        // Hook postEvent(Event) - 捕获模板 + 日志诊断 (方法名混淆, 按参数类型匹配)
        for (Method m : channelClass.getDeclaredMethods()) {
            String methodName = m.getName();
            int paramCount = m.getParameterCount();

            // postEvent: 单参数, 参数类型为 Event
            if (paramCount == 1) {
                Class<?> paramType = m.getParameterTypes()[0];
                try {
                    Class<?> eventClass = Class.forName(CLS_EVENT, false, classLoader);
                    if (paramType.isAssignableFrom(eventClass) || eventClass.isAssignableFrom(paramType)
                        || paramType.getName().equals(CLS_EVENT)) {
                        m.setAccessible(true);
                        hook(m).intercept(chain -> {
                            Object event = chain.getArg(0);
                            if (event != null) {
                                AiClientHook.captureEventTemplate(event);
                            }
                            Object result = chain.proceed();
                            try {
                                Logger.d("HookEntry: postEvent(" + m.getName() + ") result=" + result
                                    + " event=" + (event != null ? truncateStr(event.toString(), 300) : "null"));
                            } catch (Throwable ignored) {}
                            return result;
                        });
                        Logger.d("HookEntry: hooked postEvent via " + m.getName());
                    }
                } catch (Exception ignored) {}
            }

            // isConnected: 0参数, 返回 boolean
            if (paramCount == 0 && m.getReturnType() == boolean.class) {
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    boolean connected = (boolean) chain.proceed();
                    AiClientHook.onConnectionState(connected);
                    return connected;
                });
                Logger.d("HookEntry: hooked isConnected via " + m.getName());
            }

            // startConnect(boolean)
            if (methodName.equals("startConnect") && paramCount == 1) {
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    boolean force = (boolean) chain.getArg(0);
                    Logger.d("HookEntry: startConnect(" + force + ") called");
                    boolean result = (boolean) chain.proceed();
                    Logger.d("HookEntry: startConnect result=" + result);
                    return result;
                });
                Logger.d("HookEntry: hooked startConnect()");
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
    }

    // === 2. Hook InstructionWrapper (com.xiaomi.ai.core.e) ===
    private void hookInstructionWrapper(ClassLoader classLoader) {
        Class<?> iwClass = resolveClass(classLoader, CLS_INSTRUCTION_WRAPPER_NAMES);
        if (iwClass == null) {
            Logger.d("HookEntry: InstructionWrapper not found in candidates");
            return;
        }
        Logger.d("HookEntry: found InstructionWrapper class: " + iwClass.getName());

        for (Constructor<?> ctor : iwClass.getDeclaredConstructors()) {
            ctor.setAccessible(true);
            final int pc = ctor.getParameterCount();
            final Class<?>[] paramTypes = ctor.getParameterTypes();

            StringBuilder sb = new StringBuilder("IW ctor(" + pc + "): ");
            for (Class<?> p : paramTypes) {
                sb.append(p.getSimpleName()).append(", ");
            }
            Logger.d("HookEntry: " + sb.toString());

            hook(ctor).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    for (int i = 0; i < pc; i++) {
                        Object arg = chain.getArg(i);
                        if (arg instanceof String) {
                            String json = (String) arg;
                            if (json.startsWith("{") && json.length() > 10) {
                                Logger.d("HookEntry: InstructionWrapper JSON: " + truncateStr(json, 500));
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
    }

    // === 3. Hook ChannelListener (com.xiaomi.ai.core.c) ===
    // 方法名混淆, 按参数类型+数量匹配
    private void hookChannelListener(ClassLoader classLoader) {
        Class<?> listenerClass = resolveClass(classLoader, CLS_CHANNEL_LISTENER_NAMES);
        if (listenerClass == null) {
            Logger.d("HookEntry: ChannelListener not found in candidates");
            return;
        }
        Logger.d("HookEntry: found ChannelListener class: " + listenerClass.getName());

        // 先列出所有方法
        StringBuilder clMethods = new StringBuilder("ChannelListener methods: ");
        for (Method m : listenerClass.getDeclaredMethods()) {
            clMethods.append(m.getName()).append("(").append(m.getParameterCount()).append(") ");
        }
        Logger.d("HookEntry: " + clMethods.toString());

        for (Method m : listenerClass.getDeclaredMethods()) {
            m.setAccessible(true);
            final int pc = m.getParameterCount();

            // onInstruction: 2参数 (Channel, InstructionWrapper)
            if (pc == 2) {
                hook(m).intercept(chain -> {
                    try {
                        Object instrWrapper = chain.getArg(1);
                        if (instrWrapper != null) {
                            try {
                                Method getOriginal = instrWrapper.getClass().getMethod("getOriginal");
                                String json = (String) getOriginal.invoke(instrWrapper);
                                if (json != null && json.startsWith("{")) {
                                    Logger.d("HookEntry: onInstruction JSON: " + truncateStr(json, 500));
                                    AiClientHook.onInstructionJson(json);
                                }
                            } catch (Exception ignored) {}
                            AiClientHook.onInstructionObject(instrWrapper);
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                });
                Logger.d("HookEntry: hooked onInstruction via " + m.getName());
            }

            // onConnected / onDisconnected: 1参数 (Channel), 返回void
            // 无法通过签名区分, 都hook并记录
            if (pc == 1 && m.getReturnType() == void.class) {
                hook(m).intercept(chain -> {
                    Logger.d("HookEntry: ChannelListener." + m.getName() + " called");
                    AiClientHook.onConnectionState(true); // 乐观假设: 单参回调=连接成功
                    return chain.proceed();
                });
                Logger.d("HookEntry: hooked CL callback via " + m.getName());
            }

            // onError: 2参数 (Channel, Error)
            if (pc == 2) {
                // 已经在上面2参数的情况下hook了, 这里只是额外标记
                // 注意: onInstruction也是2参数, 已通过 InstructionWrapper 内容区分
            }
        }
    }

    // === 4. Hook Nlp$Request 构造函数 (捕获真实查询) ===
    private void hookNlpRequest(ClassLoader classLoader) {
        try {
            Class<?> reqClass = Class.forName(CLS_NLP_REQUEST, false, classLoader);
            Logger.d("HookEntry: found Nlp.Request class: " + reqClass.getName());

            for (Field f : reqClass.getDeclaredFields()) {
                if (!f.getName().startsWith("access$") && !f.getName().startsWith("$")) {
                    Logger.d("HookEntry: Nlp.Request field: " + f.getName() + " (" + f.getType().getSimpleName() + ")");
                }
            }

            for (Constructor<?> ctor : reqClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                final int pc = ctor.getParameterCount();
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (pc >= 1) {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof String) {
                                Logger.d("HookEntry: Nlp.Request created, query=" + truncateStr((String) arg0, 100));
                            }
                        } else {
                            Logger.d("HookEntry: Nlp.Request created (no-arg)");
                        }
                        AiClientHook.captureNlpRequest(chain.getThisObject());
                    } catch (Throwable ignored) {}
                    return result;
                });
            }
            Logger.d("HookEntry: hooked Nlp.Request constructors");
        } catch (ClassNotFoundException e) {
            Logger.d("HookEntry: Nlp.Request not found: " + CLS_NLP_REQUEST);
        }

        // 也 hook ExecuteQuery 用于诊断
        try {
            Class<?> eqClass = Class.forName(CLS_EXECUTE_QUERY, false, classLoader);
            for (Constructor<?> ctor : eqClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                final int pc = ctor.getParameterCount();
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        if (pc >= 1 && chain.getArg(0) instanceof String) {
                            Logger.d("HookEntry: ExecuteQuery created (InstructionPayload!), query="
                                + truncateStr((String) chain.getArg(0), 100));
                        }
                    } catch (Throwable ignored) {}
                    return result;
                });
            }
        } catch (ClassNotFoundException ignored) {}
    }

    // === 5. Hook APIUtils.buildEvent() - 捕获真实 Event 构造 ===
    private void hookApiUtils(ClassLoader classLoader) {
        try {
            Class<?> apiUtilsClass = Class.forName(CLS_API_UTILS, false, classLoader);
            Class<?> eventPayloadClass = Class.forName(CLS_EVENT_PAYLOAD, false, classLoader);
            Logger.d("HookEntry: found APIUtils class: " + apiUtilsClass.getName());

            // buildEvent(EventPayload) - 单参数版本
            try {
                Method buildEvent = apiUtilsClass.getDeclaredMethod("buildEvent", eventPayloadClass);
                buildEvent.setAccessible(true);
                hook(buildEvent).intercept(chain -> {
                    Object payload = chain.getArg(0);
                    Object result = chain.proceed();
                    try {
                        if (payload != null) {
                            Logger.d("HookEntry: APIUtils.buildEvent(payload="
                                + payload.getClass().getName() + ") -> Event");
                        }
                        if (result != null) {
                            AiClientHook.captureEventTemplate(result);
                        }
                    } catch (Throwable ignored) {}
                    return result;
                });
                Logger.d("HookEntry: hooked APIUtils.buildEvent(EventPayload)");
            } catch (NoSuchMethodException e) {
                Logger.d("HookEntry: buildEvent(EventPayload) not found, trying all methods");
                // 列出所有 buildEvent 方法
                for (Method m : apiUtilsClass.getDeclaredMethods()) {
                    if (m.getName().equals("buildEvent")) {
                        StringBuilder sb = new StringBuilder("APIUtils.buildEvent(");
                        for (Class<?> p : m.getParameterTypes()) {
                            sb.append(p.getSimpleName()).append(", ");
                        }
                        sb.append(")");
                        Logger.d("HookEntry: " + sb.toString());
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            Logger.d("HookEntry: APIUtils class not found: " + CLS_API_UTILS);
        }
    }

    // === 6. 诊断: 打印 Event/EventHeader/Message 类信息 ===
    private void diagnoseEventClasses(ClassLoader classLoader) {
        try {
            Class<?> eventClass = Class.forName(CLS_EVENT, false, classLoader);
            StringBuilder eFields = new StringBuilder("Event fields: ");
            for (Field f : eventClass.getDeclaredFields()) {
                if (!f.getName().startsWith("access$") && !f.getName().startsWith("$")) {
                    eFields.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
                }
            }
            Logger.d("HookEntry: " + eFields.toString());

            Class<?> messageClass = Class.forName(CLS_MESSAGE, false, classLoader);
            StringBuilder mFields = new StringBuilder("Message fields: ");
            for (Field f : messageClass.getDeclaredFields()) {
                mFields.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
            }
            Logger.d("HookEntry: " + mFields.toString());

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

    /** 回退: 搜索 Channel 类 */
    private void findChannelClass(ClassLoader classLoader) {
        try {
            for (char c = 'a'; c <= 'z'; c++) {
                try {
                    Class<?> cls = Class.forName("com.xiaomi.ai.core." + c, false, classLoader);
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals("postEvent")) {
                            Logger.d("HookEntry: found postEvent on class: " + cls.getName()
                                + " param=" + m.getParameterTypes()[0].getName());
                        }
                        if (m.getName().equals("isConnected")) {
                            Logger.d("HookEntry: found isConnected on class: " + cls.getName());
                        }
                    }
                } catch (ClassNotFoundException ignored) {}
            }
        } catch (Exception e) {
            Logger.d("HookEntry: findChannelClass failed: " + e.getMessage());
        }
    }

    private static String truncateStr(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
