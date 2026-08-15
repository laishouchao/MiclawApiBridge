package io.github.guocheng1378.miclawbridge;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.json.JSONArray;

/**
 * v3.3 Channel 方案: 通过 com.xiaomi.ai.core.b (Channel) 发送/接收消息
 *
 * 核心机制:
 * 1. setChannel() - 从 Hook 捕获 Channel 实例 (com.xiaomi.ai.core.b)
 * 2. captureEventTemplate() - 从 postEvent 捕获真实 Event 模板
 * 3. chat() - 构造 EventHeader + Nlp$ExecuteQuery, 包装进 Event, 调用 postEvent
 * 4. onInstructionJson() - 从 InstructionWrapper (com.xiaomi.ai.core.e) JSON 解析回复
 *
 * 关键类映射 (ProGuard 混淆):
 *   Channel = com.xiaomi.ai.core.b
 *   ChannelListener = com.xiaomi.ai.core.c
 *   InstructionWrapper = com.xiaomi.ai.core.e
 *   Event = com.xiaomi.ai.api.common.Event (未混淆)
 *   EventHeader = com.xiaomi.ai.api.common.EventHeader (未混淆)
 *   ExecuteQuery = com.xiaomi.ai.api.Nlp$ExecuteQuery (未混淆)
 */
public class AiClientHook {

    public interface TextSink {
        void onDelta(String text);
    }

    // 混淆类名
    private static final String CLS_CHANNEL = "com.xiaomi.ai.core.b";
    private static final String CLS_EVENT = "com.xiaomi.ai.api.common.Event";
    private static final String CLS_EVENT_HEADER = "com.xiaomi.ai.api.common.EventHeader";
    private static final String CLS_MESSAGE = "com.xiaomi.ai.api.common.Message";
    private static final String CLS_MESSAGE_HEADER = "com.xiaomi.ai.api.common.MessageHeader";
    private static final String CLS_EXECUTE_QUERY = "com.xiaomi.ai.api.Nlp$ExecuteQuery";

    private static volatile Object capturedChannel = null;
    private static volatile Object capturedListener = null;
    private static volatile Object eventTemplate = null;
    private static volatile Object executeQueryTemplate = null;
    private static volatile boolean templateDiagnosed = false;

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

    /** 由 HookEntry 调用: 捕获 Event 模板 */
    public static void captureEventTemplate(Object event) {
        if (event != null) {
            eventTemplate = event;
            if (!templateDiagnosed) {
                templateDiagnosed = true;
                diagnoseEvent(event);
            }
        }
    }

    /** 由 HookEntry 调用: 捕获 Nlp$ExecuteQuery 模板 */
    public static void captureExecuteQuery(Object eq) {
        if (eq != null) {
            executeQueryTemplate = eq;
            Logger.d("AiClientHook: captured ExecuteQuery template: " + eq.getClass().getName());
        }
    }

    /** 诊断 Event 对象的字段值 */
    private static void diagnoseEvent(Object event) {
        try {
            Logger.d("AiClientHook: === Event Template Diagnosis ===");
            Logger.d("AiClientHook: Event class: " + event.getClass().getName());
            Logger.d("AiClientHook: Event toString: " + truncate(event.toString(), 500));

            // 遍历字段 (包括父类 Message)
            Class<?> cls = event.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getName().startsWith("access$") || f.getName().startsWith("$")) continue;
                    f.setAccessible(true);
                    Object val = f.get(event);
                    String valStr = val != null ? truncate(val.toString(), 200) : "null";
                    Logger.d("AiClientHook: " + cls.getSimpleName() + "." + f.getName()
                        + " (" + f.getType().getSimpleName() + ") = " + valStr);

                    if (val != null) {
                        String fieldName = f.getName().toLowerCase();
                        if (fieldName.contains("header") || fieldName.contains("payload")) {
                            diagnoseSubObject(val, f.getName());
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
            Logger.d("AiClientHook: === End Event Diagnosis ===");
        } catch (Exception e) {
            Logger.d("AiClientHook: diagnoseEvent error: " + e.getMessage());
        }
    }

    private static void diagnoseSubObject(Object obj, String label) {
        try {
            Class<?> cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getName().startsWith("access$") || f.getName().startsWith("$")) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    String valStr = val != null ? truncate(val.toString(), 150) : "null";
                    Logger.d("AiClientHook: " + label + "." + f.getName()
                        + " (" + f.getType().getSimpleName() + ") = " + valStr);
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            Logger.d("AiClientHook: diagnoseSubObject(" + label + ") error: " + e.getMessage());
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
     */
    public static void onInstructionJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONObject header = obj.optJSONObject("header");
            if (header == null) return;

            String name = header.optString("name", "");
            String namespace = header.optString("namespace", "");
            JSONObject payload = obj.optJSONObject("payload");

            if ("System".equals(namespace) && ("Ack".equals(name) || "Abort".equals(name))) return;
            if ("SpeechRecognizer".equals(namespace)) return;
            if ("Dialog".equals(namespace) && "Finish".equals(name)) {
                onEnd();
                return;
            }

            Logger.d("AiClientHook: Instruction: " + namespace + "." + name
                + " payload=" + (payload != null ? truncate(payload.toString(), 300) : "null"));

            if ("Nlp".equals(namespace)) {
                if ("StartAnswer".equals(name) || "StartStream".equals(name)) {
                    answerStarted = true;
                    return;
                }
                if ("FinishAnswer".equals(name) || "FinishStream".equals(name)) {
                    onEnd();
                    return;
                }
            }

            // SpeechSynthesizer: 实际回复文本通过 Speak/SpeakStream 传输
            if ("SpeechSynthesizer".equals(namespace)) {
                if ("StartSpeakStream".equals(name)) {
                    answerStarted = true;
                    return;
                }
                if ("FinishSpeakStream".equals(name) || "FinishSpeak".equals(name)) {
                    onEnd();
                    return;
                }
                // Speak 和 SpeakStream 的 payload 有 text 字段
            }

            if (payload != null) {
                String text = extractTextFromPayload(payload, name, namespace);
                if (text != null && !text.isEmpty()) {
                    Logger.d("AiClientHook: extracted text: " + truncate(text, 200));
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

    /** NLP 标记事件 */
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
        String text = payload.optString("text", null);
        if (text != null && !text.isEmpty()) return text;

        text = payload.optString("answer", null);
        if (text != null && !text.isEmpty()) return text;

        text = payload.optString("content", null);
        if (text != null && !text.isEmpty()) return text;

        text = payload.optString("reply", null);
        if (text != null && !text.isEmpty()) return text;

        text = payload.optString("query", null);
        if (text != null && !text.isEmpty()) return text;

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

        JSONObject tts = payload.optJSONObject("tts");
        if (tts != null) {
            text = tts.optString("text", null);
            if (text != null && !text.isEmpty()) return text;
        }

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

        text = payload.optString("delta", null);
        if (text != null && !text.isEmpty()) return text;

        return findFirstLongString(payload);
    }

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
            if (lastReply == null) lastReply = "";
            lastReply += text;
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
     * 通过 Channel.postEvent(Event) 发送文本查询
     * 构造: EventHeader("Nlp", "ExecuteQuery") + Nlp.ExecuteQuery(text)
     */
    private static boolean sendViaPostEvent(String text) {
        try {
            Object channel = capturedChannel;
            ClassLoader cl = getHostClassLoader();

            // 1. 创建 EventHeader
            Object header = createEventHeader("Nlp", "ExecuteQuery", cl);
            if (header == null) {
                Logger.d("AiClientHook: cannot create EventHeader");
                return false;
            }
            Logger.d("AiClientHook: created EventHeader: " + header.getClass().getName());

            // 2. 创建 Nlp.ExecuteQuery payload
            Object payload = createExecuteQuery(text, cl);
            if (payload == null) {
                Logger.d("AiClientHook: cannot create ExecuteQuery, falling back to String");
                payload = text;
            } else {
                Logger.d("AiClientHook: created ExecuteQuery: " + payload.getClass().getName());
            }

            // 3. 创建 Event 并设置 header + payload
            Object event = createEvent(header, payload, cl);
            if (event == null) {
                Logger.d("AiClientHook: cannot create Event");
                return false;
            }

            Logger.d("AiClientHook: Event ready, toString=" + truncate(event.toString(), 500));

            // 4. 调用 Channel.postEvent(event)
            return callPostEvent(channel, event);

        } catch (Exception e) {
            Logger.e("AiClientHook: sendViaPostEvent failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 创建 EventHeader("Nlp", "ExecuteQuery")
     * EventHeader 有构造函数: EventHeader(String namespace, String name)
     */
    private static Object createEventHeader(String namespace, String name, ClassLoader cl) {
        try {
            Class<?> ehClass = Class.forName(CLS_EVENT_HEADER, false, cl);

            // 使用两参数构造函数: EventHeader(String, String)
            try {
                Constructor<?> ctor = ehClass.getConstructor(String.class, String.class);
                Object header = ctor.newInstance(namespace, name);

                // 设置 id
                String eventId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
                try {
                    Method setId = ehClass.getMethod("setId", String.class);
                    setId.invoke(header, eventId);
                } catch (Exception ignored) {}

                Logger.d("AiClientHook: EventHeader created: namespace=" + namespace + " name=" + name + " id=" + eventId);
                return header;
            } catch (Exception e) {
                Logger.d("AiClientHook: EventHeader(String,String) ctor failed: " + e.getMessage());
            }

            // 回退: 无参构造 + setter
            Object header = ehClass.newInstance();
            callSetter(header, ehClass, "setName", name);
            callSetter(header, ehClass, "setNamespace", namespace);
            String eventId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            callSetter(header, ehClass, "setId", eventId);
            return header;

        } catch (Exception e) {
            Logger.d("AiClientHook: createEventHeader failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 创建 Nlp.ExecuteQuery(text)
     * ExecuteQuery 有构造函数: ExecuteQuery(String query)
     * 也有 setQuery(String) 方法
     */
    private static Object createExecuteQuery(String text, ClassLoader cl) {
        try {
            Class<?> eqClass = Class.forName(CLS_EXECUTE_QUERY, false, cl);

            // 尝试 String 构造函数: ExecuteQuery(String)
            try {
                Constructor<?> ctor = eqClass.getConstructor(String.class);
                Object eq = ctor.newInstance(text);
                Logger.d("AiClientHook: ExecuteQuery created via String ctor");
                return eq;
            } catch (Exception ignored) {}

            // 回退: 无参构造 + setQuery
            Object eq = eqClass.newInstance();
            try {
                Method setQuery = eqClass.getMethod("setQuery", String.class);
                setQuery.invoke(eq, text);
                Logger.d("AiClientHook: ExecuteQuery created via setQuery");
                return eq;
            } catch (Exception ignored) {}

            // 最后回退: 直接设置字段
            Field qField = findField(eqClass, "query");
            if (qField != null) {
                qField.setAccessible(true);
                qField.set(eq, text);
                Logger.d("AiClientHook: ExecuteQuery created via field");
                return eq;
            }

            Logger.d("AiClientHook: cannot set query on ExecuteQuery");
            return null;

        } catch (ClassNotFoundException e) {
            Logger.d("AiClientHook: ExecuteQuery class not found: " + CLS_EXECUTE_QUERY);
            return null;
        } catch (Exception e) {
            Logger.d("AiClientHook: createExecuteQuery failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 创建 Event 并设置 header + payload
     * Event 有构造函数: Event(EventHeader, T payload)
     * 也可以用 Event() + setHeader() + setPayload() (来自 Message)
     */
    private static Object createEvent(Object header, Object payload, ClassLoader cl) {
        try {
            Class<?> eventClass = Class.forName(CLS_EVENT, false, cl);
            Class<?> ehClass = Class.forName(CLS_EVENT_HEADER, false, cl);

            // 方式1: 两参数构造函数 Event(EventHeader, T)
            for (Constructor<?> ctor : eventClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 2) {
                    ctor.setAccessible(true);
                    try {
                        return ctor.newInstance(header, payload);
                    } catch (Exception e) {
                        Logger.d("AiClientHook: Event(EventHeader, T) ctor failed: " + e.getMessage());
                    }
                }
            }

            // 方式2: 无参构造 + setHeader + setPayload
            Logger.d("AiClientHook: falling back to Event() + setters");
            Object event = eventClass.newInstance();

            // setHeader 来自 Message 类, 参数类型可能是 MessageHeader (父类)
            invokeSetter(event, "setHeader", header);
            invokeSetter(event, "setPayload", payload);

            return event;

        } catch (Exception e) {
            Logger.d("AiClientHook: createEvent failed: " + e.getMessage());
            return null;
        }
    }

    /** 调用 setter 方法 (搜索类层次结构) */
    private static void invokeSetter(Object obj, String methodName, Object value) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType.isAssignableFrom(value.getClass()) || paramType == Object.class) {
                        m.setAccessible(true);
                        try {
                            m.invoke(obj, value);
                            Logger.d("AiClientHook: " + methodName + " called successfully");
                            return;
                        } catch (Exception e) {
                            Logger.d("AiClientHook: " + methodName + " invoke failed: " + e.getMessage());
                        }
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        Logger.d("AiClientHook: " + methodName + " not found");
    }

    /** 调用带 String 参数的 setter */
    private static void callSetter(Object obj, Class<?> cls, String methodName, String value) {
        try {
            Method m = cls.getMethod(methodName, String.class);
            m.setAccessible(true);
            m.invoke(obj, value);
        } catch (Exception ignored) {}
    }

    /**
     * 调用 Channel.postEvent(Event)
     * postEvent 返回 boolean
     */
    private static boolean callPostEvent(Object channel, Object event) {
        try {
            Class<?> cls = channel.getClass();
            while (cls != null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("postEvent") && m.getParameterCount() == 1) {
                        Class<?> paramType = m.getParameterTypes()[0];
                        // 只匹配 Event 参数, 不匹配 d (EventWrapper) 参数
                        if (paramType.getName().equals(CLS_EVENT)
                            || paramType.isAssignableFrom(event.getClass())) {
                            m.setAccessible(true);
                            Object result = m.invoke(channel, event);
                            Logger.d("AiClientHook: postEvent invoked, result=" + result
                                + " type=" + (result != null ? result.getClass().getSimpleName() : "void"));
                            if (result instanceof Boolean) {
                                return (Boolean) result;
                            }
                            return true;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
            Logger.d("AiClientHook: postEvent(Event) not found on " + channel.getClass().getName());
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
     * 搜索 Channel 实例 (com.xiaomi.ai.core.b)
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
                    Object channel = findObjectByClassName(svc, CLS_CHANNEL);
                    if (channel != null) return channel;
                }
            }

            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            android.app.Application app = (android.app.Application) currentApp.invoke(null);
            if (app != null) {
                return findObjectByClassName(app, CLS_CHANNEL);
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
            if (cls.getName().equals(targetClassName)) {
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

    /** 查找字段 (递归父类) */
    private static Field findField(Class<?> cls, String fieldName) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
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
