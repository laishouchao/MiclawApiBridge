package io.github.guocheng1378.miclawbridge;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * v3.0 Channel 方案: 通过 com.xiaomi.ai.core.Channel 发送/接收消息
 *
 * 核心机制:
 * 1. setChannel() - 从 Hook 捕获 Channel 实例
 * 2. chat() - 通过 Channel 发送文本查询 (待实现, 需要诊断日志确认 API)
 * 3. onNlpEvent() - 从 NLP 响应类拦截 AI 回复
 */
public class AiClientHook {

    public interface TextSink {
        void onDelta(String text);
    }

    // Channel 实例 (从 Hook 捕获)
    private static volatile Object capturedChannel = null;
    // PostBack 实例 (参考用)
    private static volatile Object lastPostBack = null;
    // 旧的 AiClient 实例 (保留兼容)
    private static volatile Object capturedAiClient = null;

    /** 由 HookEntry 调用: 捕获 Channel 实例 */
    public static void setChannel(Object channel) {
        if (capturedChannel == null && channel != null) {
            capturedChannel = channel;
            Logger.d("AiClientHook: captured Channel: " + channel.getClass().getName());
        }
    }

    /** 由 HookEntry 调用: 捕获 PostBack 创建 */
    public static void onPostBackCreated(Object postBack) {
        lastPostBack = postBack;
        Logger.d("AiClientHook: PostBack captured: " + postBack.getClass().getName());
    }

    /** 由 HookEntry 调用: 处理 NLP 事件 */
    public static void onNlpEvent(String className, Object event) {
        Logger.d("AiClientHook: NLP event: " + className);
        // 尝试从事件中提取文本
        String text = extractTextFromObject(event);
        if (text != null && !text.isEmpty()) {
            Logger.d("AiClientHook: extracted text from " + className + ": " + truncate(text, 200));
            boolean isStream = className.contains("StartStream") || className.contains("LargeLanguageModel");
            boolean isEnd = className.contains("FinishStream") || className.contains("FinishAnswer");
            if (isEnd) {
                onEnd();
            } else {
                onResponse(text, isStream);
            }
        }
    }

    /** 保留兼容: 由旧的 handleInstruction hook 调用 */
    public static void setAiClient(Object aiClient) {
        if (capturedAiClient == null && aiClient != null) {
            capturedAiClient = aiClient;
            Logger.d("AiClientHook: captured AiClient (legacy): " + aiClient.getClass().getName());
        }
    }

    // 响应同步
    private static final Object lock = new Object();
    private static CountDownLatch responseLatch = null;
    private static String lastReply = null;
    private static String lastError = null;
    private static int lastFrames = 0;
    private static TextSink currentSink = null;

    public static void onResponse(String text, boolean isStreaming) {
        synchronized (lock) {
            lastFrames++;
            if (isStreaming) {
                if (lastReply == null) lastReply = "";
                lastReply += text;
            } else {
                lastReply = text;
            }
            if (currentSink != null && text != null) {
                currentSink.onDelta(text);
            }
        }
    }

    public static void onError(String error) {
        synchronized (lock) {
            lastError = error;
            if (responseLatch != null) responseLatch.countDown();
        }
    }

    public static void onEnd() {
        synchronized (lock) {
            if (responseLatch != null) responseLatch.countDown();
        }
    }

    // 缓存
    private static ClassLoader hostClassLoader = null;
    private static Object cachedActivityThread = null;
    private static java.lang.reflect.Field cachedMServicesField = null;

    private static ClassLoader getHostClassLoader() {
        if (hostClassLoader != null) return hostClassLoader;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            android.app.Application app = (android.app.Application) currentApp.invoke(null);
            if (app != null) {
                hostClassLoader = app.getClassLoader();
                return hostClassLoader;
            }
        } catch (Exception e) {
            Logger.e("AiClientHook: getHostClassLoader failed");
        }
        return AiClientHook.class.getClassLoader();
    }

    /**
     * 发送文本查询并等待响应
     */
    public static CliClient.CliResult chat(String text, String chatId, String agentId, CliClient.TextSink sink, Object images) {
        synchronized (lock) {
            lastReply = null;
            lastError = null;
            lastFrames = 0;
            responseLatch = new CountDownLatch(1);
            currentSink = (sink != null) ? sink::onDelta : null;
        }

        Logger.d("AiClientHook: chat called, text=" + text);

        // 方式1: 通过 Channel 发送 (v3.0 新方案)
        if (capturedChannel != null) {
            CliClient.CliResult result = trySendViaChannel(text, chatId, agentId);
            if (result != null) return result;
        }

        // 方式2: 搜索 Channel 实例 (可能尚未捕获)
        if (capturedChannel == null) {
            Object channel = findChannelInstance();
            if (channel != null) {
                capturedChannel = channel;
                Logger.d("AiClientHook: found Channel via search: " + channel.getClass().getName());
                CliClient.CliResult result = trySendViaChannel(text, chatId, agentId);
                if (result != null) return result;
            }
        }

        // 方式3: 通过 Intent 触发文本查询 (回退)
        CliClient.CliResult intentResult = trySendViaIntent(text);
        if (intentResult != null) return intentResult;

        synchronized (lock) {
            String error = lastError != null ? lastError : "Channel not available and Intent fallback failed";
            return new CliClient.CliResult("", error, chatId, 0);
        }
    }

    /**
     * 通过 Channel 发送文本查询
     */
    private static CliClient.CliResult trySendViaChannel(String text, String chatId, String agentId) {
        try {
            Object channel = capturedChannel;
            Class<?> channelClass = channel.getClass();
            ClassLoader cl = getHostClassLoader();

            // 尝试构造 SpeechRecognizer$PostBack 事件
            try {
                Class<?> postBackClass = Class.forName("com.xiaomi.ai.api.SpeechRecognizer$PostBack", false, cl);
                Logger.d("AiClientHook: PostBack class found, constructors:");
                for (java.lang.reflect.Constructor<?> ctor : postBackClass.getDeclaredConstructors()) {
                    StringBuilder sb = new StringBuilder("  ctor(");
                    for (Class<?> p : ctor.getParameterTypes()) sb.append(p.getName()).append(", ");
                    sb.append(")");
                    Logger.d(sb.toString());
                }

                // 尝试无参构造函数
                java.lang.reflect.Constructor<?> noArg = null;
                for (java.lang.reflect.Constructor<?> ctor : postBackClass.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 0) { noArg = ctor; break; }
                }

                Object postBack = null;
                if (noArg != null) {
                    noArg.setAccessible(true);
                    postBack = noArg.newInstance();
                    Logger.d("AiClientHook: created PostBack via no-arg ctor");
                } else {
                    // 尝试单参数 (String) 构造函数
                    for (java.lang.reflect.Constructor<?> ctor : postBackClass.getDeclaredConstructors()) {
                        if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0] == String.class) {
                            ctor.setAccessible(true);
                            postBack = ctor.newInstance(text);
                            Logger.d("AiClientHook: created PostBack via String ctor");
                            break;
                        }
                    }
                }

                if (postBack != null) {
                    // 尝试设置文本内容
                    setField(postBack, "text", text);
                    setField(postBack, "query", text);
                    setField(postBack, "content", text);
                    callSetter(postBack, "setText", text);
                    callSetter(postBack, "setQuery", text);
                    callSetter(postBack, "setContent", text);

                    // 通过 Channel 发送
                    boolean sent = sendThroughChannel(channel, postBack);
                    if (sent) {
                        Logger.d("AiClientHook: sent via Channel, waiting response...");
                        boolean completed = responseLatch.await(Config.READ_TIMEOUT, TimeUnit.MILLISECONDS);
                        synchronized (lock) {
                            String reply = lastReply != null ? lastReply : "";
                            String error = lastError;
                            if (!completed && error == null) {
                                error = "TIMEOUT: no response within " + Config.READ_TIMEOUT + "ms";
                            }
                            Logger.d("AiClientHook: done, len=" + reply.length() + " frames=" + lastFrames + " error=" + error);
                            return new CliClient.CliResult(reply, error, chatId, lastFrames);
                        }
                    }
                }
            } catch (Exception e) {
                Logger.d("AiClientHook: PostBack approach failed: " + e.getMessage());
            }

            // 尝试 Nlp$ExecuteQuery
            try {
                Class<?> executeQueryClass = Class.forName("com.xiaomi.ai.api.Nlp$ExecuteQuery", false, cl);
                Logger.d("AiClientHook: ExecuteQuery class found");
                // 构造并设置查询文本
                for (java.lang.reflect.Constructor<?> ctor : executeQueryClass.getDeclaredConstructors()) {
                    ctor.setAccessible(true);
                    if (ctor.getParameterCount() == 0) {
                        Object query = ctor.newInstance();
                        setField(query, "query", text);
                        setField(query, "text", text);
                        callSetter(query, "setQuery", text);
                        callSetter(query, "setText", text);
                        if (sendThroughChannel(channel, query)) {
                            Logger.d("AiClientHook: sent ExecuteQuery via Channel");
                            boolean completed = responseLatch.await(Config.READ_TIMEOUT, TimeUnit.MILLISECONDS);
                            synchronized (lock) {
                                String reply = lastReply != null ? lastReply : "";
                                String error = lastError;
                                if (!completed && error == null) error = "TIMEOUT";
                                return new CliClient.CliResult(reply, error, chatId, lastFrames);
                            }
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Logger.d("AiClientHook: ExecuteQuery approach failed: " + e.getMessage());
            }

        } catch (Exception e) {
            Logger.e("AiClientHook: trySendViaChannel failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 通过 Channel 发送事件
     */
    private static boolean sendThroughChannel(Object channel, Object event) {
        Class<?> channelClass = channel.getClass();
        Class<?> eventClass = event.getClass();

        // 查找发送方法: send, sendEvent, sendInstruction, sendEventInternal 等
        String[] sendMethodNames = {
            "sendEvent", "sendInstruction", "send", "sendEventInternal",
            "dispatchEvent", "dispatchInstruction", "publish", "submit"
        };

        for (String methodName : sendMethodNames) {
            for (Method m : channelClass.getMethods()) {
                if (methodName.equals(m.getName()) && m.getParameterCount() >= 1) {
                    Class<?> firstParam = m.getParameterTypes()[0];
                    if (firstParam.isAssignableFrom(eventClass) || firstParam == Object.class) {
                        try {
                            m.setAccessible(true);
                            Object[] args = new Object[m.getParameterCount()];
                            args[0] = event;
                            for (int i = 1; i < args.length; i++) {
                                Class<?> pt = m.getParameterTypes()[i];
                                if (pt == int.class) args[i] = 0;
                                else if (pt == boolean.class) args[i] = false;
                                else if (pt == String.class) args[i] = "";
                                else args[i] = null;
                            }
                            m.invoke(channel, args);
                            Logger.d("AiClientHook: sent via " + methodName + "()");
                            return true;
                        } catch (Exception e) {
                            Logger.d("AiClientHook: " + methodName + " failed: " + e.getMessage());
                        }
                    }
                }
            }
        }

        Logger.d("AiClientHook: no suitable send method on Channel for " + eventClass.getName());
        return false;
    }

    /**
     * 通过 Intent 发送文本查询 (回退方案)
     */
    private static CliClient.CliResult trySendViaIntent(String text) {
        try {
            Logger.d("AiClientHook: trying Intent fallback");
            android.content.Intent intent = new android.content.Intent("android.intent.action.ASSIST");
            intent.putExtra("android.intent.extra.ASSIST_INPUT_TEXT", text);
            intent.putExtra("query", text);
            intent.setPackage("com.miui.voiceassist");

            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            Context ctx = (Context) currentApp.invoke(null);
            if (ctx != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                Logger.d("AiClientHook: sent via Intent, waiting response...");
                boolean completed = responseLatch.await(Config.READ_TIMEOUT, TimeUnit.MILLISECONDS);
                synchronized (lock) {
                    String reply = lastReply != null ? lastReply : "";
                    String error = lastError;
                    if (!completed && error == null) error = "TIMEOUT: Intent fallback";
                    return new CliClient.CliResult(reply, error, null, lastFrames);
                }
            }
        } catch (Exception e) {
            Logger.d("AiClientHook: Intent fallback failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 搜索 Channel 实例
     */
    private static Object findChannelInstance() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread", false, getHostClassLoader());
            if (cachedActivityThread == null) {
                Method currentAt = atClass.getDeclaredMethod("currentActivityThread");
                currentAt.setAccessible(true);
                cachedActivityThread = currentAt.invoke(null);
            }
            if (cachedMServicesField == null) {
                cachedMServicesField = atClass.getDeclaredField("mServices");
                cachedMServicesField.setAccessible(true);
            }
            if (cachedActivityThread != null) {
                @SuppressWarnings("unchecked")
                java.util.Map<android.os.IBinder, android.app.Service> services =
                    (java.util.Map<android.os.IBinder, android.app.Service>) cachedMServicesField.get(cachedActivityThread);
                if (services != null) {
                    for (android.app.Service svc : services.values()) {
                        Object channel = findObjectByClassName(svc, "com.xiaomi.ai.core.Channel");
                        if (channel != null) return channel;
                    }
                }
            }

            // 也搜索 Application
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            android.app.Application app = (android.app.Application) currentApp.invoke(null);
            if (app != null) {
                Object channel = findObjectByClassName(app, "com.xiaomi.ai.core.Channel");
                if (channel != null) return channel;
            }
        } catch (Exception e) {
            Logger.e("AiClientHook: findChannelInstance failed: " + e.getMessage());
        }
        return null;
    }

    private static Object findObjectByClassName(Object root, String targetClassName) {
        return findObjectByClassName(root, targetClassName, 0, new java.util.IdentityHashMap<>());
    }

    private static Object findObjectByClassName(Object obj, String targetClassName, int depth, java.util.IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 8 || visited.containsKey(obj)) return null;
        visited.put(obj, Boolean.TRUE);

        Class<?> cls = obj.getClass();
        while (cls != null) {
            if (cls.getName().equals(targetClassName) || cls.getName().contains(targetClassName.substring(targetClassName.lastIndexOf('.') + 1))) {
                return obj;
            }
            cls = cls.getSuperclass();
        }

        cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                Class<?> type = f.getType();
                if (type.isPrimitive() || type == String.class || type == Class.class) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(obj);
                    if (val != null && !visited.containsKey(val)) {
                        Object found = findObjectByClassName(val, targetClassName, depth + 1, visited);
                        if (found != null) return found;
                    }
                } catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // === 工具方法 ===

    private static void setField(Object obj, String fieldName, String value) {
        try {
            Class<?> cls = obj.getClass();
            while (cls != null) {
                try {
                    Field f = cls.getDeclaredField(fieldName);
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        f.set(obj, value);
                        Logger.d("AiClientHook: set " + fieldName + " = " + truncate(value, 50));
                        return;
                    }
                } catch (NoSuchFieldException ignored) {}
                cls = cls.getSuperclass();
            }
        } catch (Exception ignored) {}
    }

    private static void callSetter(Object obj, String methodName, String value) {
        try {
            Method m = obj.getClass().getMethod(methodName, String.class);
            m.setAccessible(true);
            m.invoke(obj, value);
            Logger.d("AiClientHook: called " + methodName + "()");
        } catch (Exception ignored) {}
    }

    private static String extractTextFromObject(Object obj) {
        if (obj == null) return null;
        try {
            Class<?> cls = obj.getClass();
            // 尝试 getter 方法
            String[] getters = {"getText", "getQuery", "getContent", "getAnswer", "getReply", "getData", "getMessage"};
            for (String getter : getters) {
                try {
                    Method m = cls.getMethod(getter);
                    m.setAccessible(true);
                    Object val = m.invoke(obj);
                    if (val instanceof String && !((String) val).isEmpty()) {
                        return (String) val;
                    }
                    if (val != null) {
                        String s = val.toString();
                        if (s.length() > 5) return s;
                    }
                } catch (NoSuchMethodException ignored) {}
            }
            // 尝试字段
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == String.class) {
                        String val = (String) f.get(obj);
                        if (val != null && val.length() > 2) {
                            return val;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    public static CliClient.CliResult chat(String text, String chatId, String agentId, CliClient.TextSink sink) {
        return chat(text, chatId, agentId, sink, null);
    }

    public static CliClient.CliResult chat(String text, String chatId, String agentId) {
        return chat(text, chatId, agentId, null, null);
    }
}
