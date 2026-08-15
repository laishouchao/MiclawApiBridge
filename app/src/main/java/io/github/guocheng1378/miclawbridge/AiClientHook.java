package io.github.guocheng1378.miclawbridge;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.json.JSONArray;

/**
 * v3.1 Channel 方案: 通过 com.xiaomi.ai.core.Channel 发送/接收消息
 *
 * 核心机制:
 * 1. setChannel() - 从 Hook 捕获 Channel 实例
 * 2. chat() - 通过 Channel.postEvent(Nlp$ExecuteQuery) 发送文本查询
 * 3. onInstructionJson() - 从 InstructionWrapper JSON 解析 AI 回复文本
 * 4. onNlpMarker() - 跟踪 StartAnswer/FinishAnswer 等标记事件
 */
public class AiClientHook {

    public interface TextSink {
        void onDelta(String text);
    }

    private static volatile Object capturedChannel = null;
    private static volatile Object capturedListener = null;

    /** 由 HookEntry 调用: 捕获 Channel 实例 */
    public static void setChannel(Object channel) {
        if (channel != null) {
            capturedChannel = channel;
            Logger.d("AiClientHook: captured Channel: " + channel.getClass().getName());
        }
    }

    /** 由 HookEntry 调用: 捕获 ChannelListener 实例 */
    public static void setChannelListener(Object listener) {
        if (listener != null && capturedListener == null) {
            capturedListener = listener;
            Logger.d("AiClientHook: captured ChannelListener: " + listener.getClass().getName());
        }
    }

    // === 响应同步 ===
    private static final Object lock = new Object();
    private static CountDownLatch responseLatch = null;
    private static String lastReply = null;
    private static String lastError = null;
    private static int lastFrames = 0;
    private static TextSink currentSink = null;
    private static boolean answerStarted = false;

    /**
     * 处理 InstructionWrapper 的 JSON
     * JSON 格式: {"header":{"name":"...","namespace":"...","dialog_id":"...","id":"..."},"payload":{...}}
     */
    public static void onInstructionJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONObject header = obj.optJSONObject("header");
            if (header == null) return;

            String name = header.optString("name", "");
            String namespace = header.optString("namespace", "");
            JSONObject payload = obj.optJSONObject("payload");

            // 跳过非回复指令
            if ("System".equals(namespace) && ("Ack".equals(name) || "Abort".equals(name))) return;
            if ("SpeechRecognizer".equals(namespace)) return; // ASR 结果, 非回复
            if ("Dialog".equals(namespace) && "Finish".equals(name)) {
                // 对话结束
                onEnd();
                return;
            }

            Logger.d("AiClientHook: Instruction: " + namespace + "." + name + " payload=" + (payload != null ? payload.toString() : "null"));

            // NLP 回复标记
            if ("Nlp".equals(namespace)) {
                if ("StartAnswer".equals(name)) {
                    answerStarted = true;
                    return;
                }
                if ("FinishAnswer".equals(name)) {
                    onEnd();
                    return;
                }
            }

            // 从 payload 中提取文本
            if (payload != null) {
                String text = extractTextFromPayload(payload, name, namespace);
                if (text != null && !text.isEmpty()) {
                    Logger.d("AiClientHook: extracted text from " + namespace + "." + name + ": " + truncate(text, 200));
                    onResponse(text, false);
                }
            }
        } catch (Exception e) {
            Logger.d("AiClientHook: JSON parse error: " + e.getMessage());
        }
    }

    /** 由 ChannelListener.onInstruction hook 调用 */
    public static void onInstructionObject(Object instruction) {
        if (instruction == null) return;
        try {
            String str = instruction.toString();
            if (str.startsWith("{")) {
                onInstructionJson(str);
            }
        } catch (Exception ignored) {}
    }

    /** NLP 标记事件 (StartAnswer/FinishAnswer/StartStream/FinishStream) */
    public static void onNlpMarker(String name) {
        Logger.d("AiClientHook: NLP marker: " + name);
        if ("StartAnswer".equals(name) || "StartStream".equals(name)) {
            answerStarted = true;
        } else if ("FinishAnswer".equals(name) || "FinishStream".equals(name)) {
            onEnd();
        }
    }

    /**
     * 从 payload JSON 中提取文本
     */
    private static String extractTextFromPayload(JSONObject payload, String instructionName, String namespace) {
        // 直接检查 text 字段
        String text = payload.optString("text", null);
        if (text != null && !text.isEmpty()) return text;

        // 检查 answer 字段
        text = payload.optString("answer", null);
        if (text != null && !text.isEmpty()) return text;

        // 检查 content 字段
        text = payload.optString("content", null);
        if (text != null && !text.isEmpty()) return text;

        // 检查 reply 字段
        text = payload.optString("reply", null);
        if (text != null && !text.isEmpty()) return text;

        // 检查 data.tts.text 字段 (TTS 回复)
        JSONObject data = payload.optJSONObject("data");
        if (data != null) {
            text = data.optString("text", null);
            if (text != null && !text.isEmpty()) return text;
            JSONObject tts = data.optJSONObject("tts");
            if (tts != null) {
                text = tts.optString("text", null);
                if (text != null && !text.isEmpty()) return text;
            }
        }

        // 检查 tts.text 字段
        JSONObject tts = payload.optJSONObject("tts");
        if (tts != null) {
            text = tts.optString("text", null);
            if (text != null && !text.isEmpty()) return text;
        }

        // 检查 results 数组 (可能用于流式回复)
        JSONArray results = payload.optJSONArray("results");
        if (results != null && results.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r != null) {
                    String t = r.optString("text", null);
                    if (t != null && !t.isEmpty()) sb.append(t);
                }
            }
            if (sb.length() > 0) return sb.toString();
        }

        // 检查流式字段
        text = payload.optString("delta", null);
        if (text != null && !text.isEmpty()) return text;

        text = payload.optString("chunk", null);
        if (text != null && !text.isEmpty()) return text;

        // 递归检查 payload 中的所有字符串字段
        return findFirstLongString(payload);
    }

    /** 递归查找 JSON 中第一个长度 > 5 的字符串值 */
    private static String findFirstLongString(JSONObject obj) {
        try {
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = obj.get(key);
                if (val instanceof String) {
                    String s = (String) val;
                    if (s.length() > 5) return s;
                } else if (val instanceof JSONObject) {
                    String found = findFirstLongString((JSONObject) val);
                    if (found != null) return found;
                } else if (val instanceof JSONArray) {
                    JSONArray arr = (JSONArray) val;
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.opt(i);
                        if (item instanceof String && ((String) item).length() > 5) {
                            return (String) item;
                        } else if (item instanceof JSONObject) {
                            String found = findFirstLongString((JSONObject) item);
                            if (found != null) return found;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // === 响应回调 ===

    public static void onResponse(String text, boolean isStreaming) {
        synchronized (lock) {
            lastFrames++;
            if (isStreaming) {
                if (lastReply == null) lastReply = "";
                lastReply += text;
            } else {
                if (lastReply == null) lastReply = "";
                lastReply += text;
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

    // === 发送 ===

    private static ClassLoader hostClassLoader = null;

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

    /** 等待响应 (包装 InterruptedException) */
    private static boolean awaitResponse() {
        try {
            return responseLatch.await(Config.READ_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 发送文本查询并等待响应
     */
    public static CliClient.CliResult chat(String text, String chatId, String agentId, CliClient.TextSink sink, Object images) {
        synchronized (lock) {
            lastReply = null;
            lastError = null;
            lastFrames = 0;
            answerStarted = false;
            responseLatch = new CountDownLatch(1);
            currentSink = (sink != null) ? sink::onDelta : null;
        }

        Logger.d("AiClientHook: chat called, text=" + truncate(text, 100));

        // 方式1: 通过 Channel.postEvent 发送
        if (capturedChannel != null) {
            boolean sent = sendViaPostEvent(text);
            if (sent) {
                Logger.d("AiClientHook: sent via postEvent, waiting response...");
                boolean completed = awaitResponse();
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

        // 方式2: 搜索 Channel 实例
        if (capturedChannel == null) {
            Object channel = findChannelInstance();
            if (channel != null) {
                capturedChannel = channel;
                Logger.d("AiClientHook: found Channel via search: " + channel.getClass().getName());
                boolean sent = sendViaPostEvent(text);
                if (sent) {
                    Logger.d("AiClientHook: sent via postEvent (searched), waiting...");
                    boolean completed = awaitResponse();
                    synchronized (lock) {
                        String reply = lastReply != null ? lastReply : "";
                        String error = lastError;
                        if (!completed && error == null) error = "TIMEOUT";
                        return new CliClient.CliResult(reply, error, chatId, lastFrames);
                    }
                }
            }
        }

        // 方式3: Intent 回退
        Logger.d("AiClientHook: Channel send failed, trying Intent fallback");
        trySendViaIntent(text);
        boolean completed = awaitResponse();
        synchronized (lock) {
            String reply = lastReply != null ? lastReply : "";
            String error = lastError;
            if (!completed && error == null) error = "TIMEOUT: Intent fallback";
            return new CliClient.CliResult(reply, error, chatId, lastFrames);
        }
    }

    /**
     * 通过 Channel.postEvent(Event) 发送 Nlp$ExecuteQuery
     */
    private static boolean sendViaPostEvent(String text) {
        try {
            Object channel = capturedChannel;
            ClassLoader cl = getHostClassLoader();

            // 创建 Nlp$ExecuteQuery 事件
            Class<?> executeQueryClass = Class.forName("com.xiaomi.ai.api.Nlp$ExecuteQuery", false, cl);
            Object event = null;

            // 尝试无参构造函数
            for (java.lang.reflect.Constructor<?> ctor : executeQueryClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 0) {
                    ctor.setAccessible(true);
                    event = ctor.newInstance();
                    break;
                }
            }
            if (event == null) {
                // 尝试 String 参数构造函数
                for (java.lang.reflect.Constructor<?> ctor : executeQueryClass.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 1) {
                        ctor.setAccessible(true);
                        try {
                            event = ctor.newInstance(text);
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (event == null) {
                Logger.d("AiClientHook: cannot create Nlp$ExecuteQuery instance");
                return false;
            }

            // 设置查询文本
            try {
                Method setQuery = executeQueryClass.getDeclaredMethod("setQuery", String.class);
                setQuery.setAccessible(true);
                setQuery.invoke(event, text);
                Logger.d("AiClientHook: set query text on ExecuteQuery");
            } catch (Exception e) {
                Logger.d("AiClientHook: setQuery failed: " + e.getMessage());
            }

            // 调用 Channel.postEvent(event)
            return callPostEvent(channel, event);

        } catch (Exception e) {
            Logger.e("AiClientHook: sendViaPostEvent failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 调用 Channel.postEvent(Event)
     */
    private static boolean callPostEvent(Object channel, Object event) {
        try {
            // 获取 postEvent 方法 (可能在实现类中, 不在抽象基类)
            Class<?> channelClass = channel.getClass();
            Method postEvent = null;

            // 搜索 channel 实例的类及其父类
            Class<?> cls = channelClass;
            while (cls != null && postEvent == null) {
                try {
                    postEvent = cls.getDeclaredMethod("postEvent", event.getClass());
                } catch (NoSuchMethodException e) {
                    // 尝试用 Event 基类作为参数类型
                    try {
                        Class<?> eventBase = Class.forName("com.xiaomi.ai.api.common.Event", false, getHostClassLoader());
                        postEvent = cls.getDeclaredMethod("postEvent", eventBase);
                    } catch (Exception e2) {
                        // 尝试所有 postEvent 方法
                        for (Method m : cls.getDeclaredMethods()) {
                            if (m.getName().equals("postEvent") && m.getParameterCount() == 1) {
                                Class<?> paramType = m.getParameterTypes()[0];
                                if (paramType.isAssignableFrom(event.getClass())) {
                                    postEvent = m;
                                    break;
                                }
                            }
                        }
                    }
                }
                cls = cls.getSuperclass();
            }

            if (postEvent != null) {
                postEvent.setAccessible(true);
                Object result = postEvent.invoke(channel, event);
                Logger.d("AiClientHook: postEvent() called, result=" + result);
                return true;
            } else {
                // 最后尝试: 用 getMethod 搜索公开方法
                try {
                    Class<?> eventBase = Class.forName("com.xiaomi.ai.api.common.Event", false, getHostClassLoader());
                    postEvent = channelClass.getMethod("postEvent", eventBase);
                    postEvent.setAccessible(true);
                    postEvent.invoke(channel, event);
                    Logger.d("AiClientHook: postEvent() called via getMethod");
                    return true;
                } catch (Exception e) {
                    Logger.d("AiClientHook: postEvent not found: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Logger.e("AiClientHook: callPostEvent failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * 通过 Intent 发送文本查询 (回退方案)
     */
    private static void trySendViaIntent(String text) {
        try {
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
                Logger.d("AiClientHook: sent via Intent");
            }
        } catch (Exception e) {
            Logger.d("AiClientHook: Intent fallback failed: " + e.getMessage());
            synchronized (lock) {
                lastError = "Intent fallback failed: " + e.getMessage();
                if (responseLatch != null) responseLatch.countDown();
            }
        }
    }

    /**
     * 搜索 Channel 实例 (从 ActivityThread 的 Service 中)
     */
    private static Object findChannelInstance() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread", false, getHostClassLoader());
            Method currentAt = atClass.getDeclaredMethod("currentActivityThread");
            currentAt.setAccessible(true);
            Object at = currentAt.invoke(null);
            if (at == null) return null;

            Field mServicesField = atClass.getDeclaredField("mServices");
            mServicesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<android.os.IBinder, android.app.Service> services =
                (java.util.Map<android.os.IBinder, android.app.Service>) mServicesField.get(at);
            if (services != null) {
                for (android.app.Service svc : services.values()) {
                    Object channel = findObjectByClassName(svc, "com.xiaomi.ai.core.Channel");
                    if (channel != null) return channel;
                }
            }

            // 也搜索 Application
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            android.app.Application app = (android.app.Application) currentApp.invoke(null);
            if (app != null) {
                return findObjectByClassName(app, "com.xiaomi.ai.core.Channel");
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
