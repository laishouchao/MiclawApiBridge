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
 * v3.4 Channel 方案: 通过 com.xiaomi.ai.core.b (Channel) 发送 Nlp.Request 事件
 *
 * 关键修正 (v3.4):
 * - 使用 Nlp.Request (implements EventPayload) 替代 Nlp.ExecuteQuery (implements InstructionPayload)
 * - 使用 APIUtils.buildEvent(request) 构造 Event (正确处理 @NamespaceName 注解)
 * - 发送前检查 Channel.isConnected()
 * - Hook onConnected/onDisconnected 跟踪连接状态
 *
 * 核心机制:
 * 1. setChannel() - 从 Hook 捕获 Channel 实例 (com.xiaomi.ai.core.b)
 * 2. onConnectionState() - 从 Hook 跟踪连接状态
 * 3. captureEventTemplate() - 从 postEvent/buildEvent 捕获真实 Event 模板
 * 4. chat() - 构造 Nlp.Request -> APIUtils.buildEvent -> channel.postEvent(event)
 * 5. onInstructionJson() - 从 InstructionWrapper JSON 解析回复 (SpeechSynthesizer.Speak.text)
 *
 * 类映射:
 *   Channel = com.xiaomi.ai.core.b (混淆)
 *   ChannelListener = com.xiaomi.ai.core.c (混淆)
 *   InstructionWrapper = com.xiaomi.ai.core.e (混淆)
 *   Event = com.xiaomi.ai.api.common.Event (未混淆)
 *   EventHeader = com.xiaomi.ai.api.common.EventHeader (未混淆)
 *   APIUtils = com.xiaomi.ai.api.common.APIUtils (未混淆)
 *   Nlp.Request = com.xiaomi.ai.api.Nlp$Request (未混淆, implements EventPayload)
 */
public class AiClientHook {

    public interface TextSink {
        void onDelta(String text);
    }

    // 混淆类名
    private static final String CLS_CHANNEL = "com.xiaomi.ai.core.b";
    // API 类名 (未混淆)
    private static final String CLS_EVENT = "com.xiaomi.ai.api.common.Event";
    private static final String CLS_EVENT_HEADER = "com.xiaomi.ai.api.common.EventHeader";
    private static final String CLS_MESSAGE = "com.xiaomi.ai.api.common.Message";
    private static final String CLS_API_UTILS = "com.xiaomi.ai.api.common.APIUtils";
    private static final String CLS_EVENT_PAYLOAD = "com.xiaomi.ai.api.common.EventPayload";
    private static final String CLS_NLP_REQUEST = "com.xiaomi.ai.api.Nlp$Request";

    // 状态
    private static volatile Object capturedChannel = null;
    private static volatile boolean channelConnected = false;
    private static volatile Object eventTemplate = null;
    private static volatile Object nlpRequestTemplate = null;
    private static volatile boolean templateDiagnosed = false;

    // === 由 HookEntry 调用的回调 ===

    public static void setChannel(Object channel) {
        if (channel != null) {
            capturedChannel = channel;
            Logger.d("AiClientHook: captured Channel: " + channel.getClass().getName());
            checkConnectionState();
        }
    }

    public static void onConnectionState(boolean connected) {
        if (connected != channelConnected) {
            channelConnected = connected;
            Logger.d("AiClientHook: connection state: " + connected);
        }
    }

    public static void captureEventTemplate(Object event) {
        if (event != null) {
            eventTemplate = event;
            if (!templateDiagnosed) {
                templateDiagnosed = true;
                diagnoseEvent(event);
            }
        }
    }

    public static void captureNlpRequest(Object request) {
        if (request != null) {
            nlpRequestTemplate = request;
            Logger.d("AiClientHook: captured Nlp.Request template: " + request.getClass().getName());
        }
    }

    /** 检查 Channel 连接状态 (方法名混淆, 先查字段再查方法) */
    private static void checkConnectionState() {
        Object channel = capturedChannel;
        if (channel == null) return;
        try {
            boolean result = isChannelConnected();
            onConnectionState(result);
            Logger.d("AiClientHook: checkConnectionState result=" + result);
        } catch (Exception e) {
            Logger.d("AiClientHook: checkConnectionState failed: " + e.getMessage());
        }
    }

    /** 通过反射检查 Channel 是否已连接 (优先查字段, 再查方法, 最后默认 true) */
    private static boolean isChannelConnected() {
        Object channel = capturedChannel;
        if (channel == null) return false;
        try {
            Class<?> cls = channel.getClass();

            // 方式1: 查找 boolean 字段 (connected, mConnected, mIsConnected, isConnected)
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    String fn = f.getName().toLowerCase();
                    if (f.getType() == boolean.class || f.getType() == Boolean.class) {
                        if (fn.contains("connect") || fn.contains("active") || fn.contains("open")
                            || fn.contains("alive") || fn.contains("ready")) {
                            f.setAccessible(true);
                            Object val = f.get(channel);
                            boolean connected = val instanceof Boolean ? (Boolean) val : false;
                            Logger.d("AiClientHook: isChannelConnected via field " + f.getName() + "=" + connected);
                            return connected;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }

            // 方式2: 查找 boolean() 方法
            cls = channel.getClass();
            Method isConnected = findMethodBySignature(cls, boolean.class, 0);
            if (isConnected != null) {
                isConnected.setAccessible(true);
                boolean result = (boolean) isConnected.invoke(channel);
                Logger.d("AiClientHook: isChannelConnected via method " + isConnected.getName() + "=" + result);
                return result;
            }

            // 方式3: 查找 boolean isXxx() 方法 (常见命名: isConnected, isOpen, isAlive, isReady)
            while (cls != null && cls != Object.class) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getReturnType() == boolean.class && m.getParameterCount() == 0) {
                        String mn = m.getName().toLowerCase();
                        if (mn.startsWith("is") && (mn.contains("connect") || mn.contains("active")
                            || mn.contains("open") || mn.contains("alive") || mn.contains("ready"))) {
                            m.setAccessible(true);
                            boolean result = (boolean) m.invoke(channel);
                            Logger.d("AiClientHook: isChannelConnected via " + m.getName() + "=" + result);
                            return result;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }

            Logger.d("AiClientHook: isChannelConnected: no method/field found, assuming connected=true");
            return true;  // 找不到状态检测方法时, 乐观假设已连接

        } catch (Exception e) {
            Logger.d("AiClientHook: isChannelConnected failed: " + e.getMessage());
            return true;  // 异常时也乐观假设已连接
        }
    }

    // === 诊断 ===

    private static void diagnoseEvent(Object event) {
        try {
            Logger.d("AiClientHook: === Event Template Diagnosis ===");
            Logger.d("AiClientHook: Event class: " + event.getClass().getName());
            Logger.d("AiClientHook: Event toString: " + truncate(event.toString(), 500));

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

    // === 响应处理 ===

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
                if ("StartAnswer".equals(name) || "StartStream".equals(name) || "StartPreStream".equals(name)) {
                    answerStarted = true;
                    return;
                }
                if ("FinishAnswer".equals(name) || "FinishStream".equals(name) || "FinishPreStream".equals(name)) {
                    onEnd();
                    return;
                }
            }

            if ("SpeechSynthesizer".equals(namespace)) {
                if ("StartSpeakStream".equals(name)) {
                    answerStarted = true;
                    return;
                }
                if ("FinishSpeakStream".equals(name) || "FinishSpeak".equals(name)) {
                    onEnd();
                    return;
                }
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

    public static void onInstructionObject(Object instruction) {
        if (instruction == null) return;
        try {
            String str = instruction.toString();
            if (str.startsWith("{")) {
                onInstructionJson(str);
            }
        } catch (Exception ignored) {}
    }

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

        // 方式1: 通过 Channel.postEvent 发送 (Nlp.Request via APIUtils.buildEvent)
        if (capturedChannel != null) {
            boolean connected = isChannelConnected();
            Logger.d("AiClientHook: channel captured, connected=" + connected);

            if (connected) {
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
            } else {
                Logger.d("AiClientHook: channel not connected, trying send anyway...");
                // 即使未连接也尝试发送, 可能发送时已连接
                boolean sent = sendViaPostEvent(text);
                if (sent) {
                    Logger.d("AiClientHook: sent via postEvent (was disconnected), waiting...");
                    boolean completed = awaitResponse();
                    synchronized (lock) {
                        String reply = lastReply != null ? lastReply : "";
                        String error = lastError;
                        if (!completed && error == null) error = "TIMEOUT";
                        return new CliClient.CliResult(reply, error, chatId, lastFrames);
                    }
                }
            }
        } else {
            Logger.d("AiClientHook: no channel captured, trying search...");
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

        // 方式2: Intent 回退
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
     *
     * 构造流程:
     * 1. 创建 Nlp.Request(text) - implements EventPayload, has @Required query field
     * 2. 调用 APIUtils.buildEvent(request) - 读取 @NamespaceName 注解, 创建 EventHeader + Event
     * 3. 调用 channel.postEvent(event) - 序列化为 JSON, 包装进 EventWrapper, 通过 WebSocket 发送
     */
    private static boolean sendViaPostEvent(String text) {
        try {
            Object channel = capturedChannel;
            ClassLoader cl = getHostClassLoader();

            // 1. 创建 Nlp.Request(text)
            Object request = createNlpRequest(text, cl);
            if (request == null) {
                Logger.d("AiClientHook: cannot create Nlp.Request");
                return false;
            }
            Logger.d("AiClientHook: created Nlp.Request: " + request.getClass().getName());

            // 2. 构造 Event (优先用 APIUtils.buildEvent)
            Object event = buildEventViaApiUtils(request, cl);
            if (event == null) {
                // 回退: 手动构造
                Logger.d("AiClientHook: APIUtils.buildEvent failed, trying manual construction");
                event = buildEventManual(request, cl);
            }
            if (event == null) {
                Logger.d("AiClientHook: cannot create Event");
                return false;
            }

            Logger.d("AiClientHook: Event ready, toString=" + truncate(event.toString(), 500));

            // 3. 调用 channel.postEvent(event)
            return callPostEvent(channel, event);

        } catch (Exception e) {
            Logger.e("AiClientHook: sendViaPostEvent failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 创建 Nlp.Request(text)
     * Nlp.Request implements EventPayload
     * 构造函数: Request(String query)
     */
    private static Object createNlpRequest(String text, ClassLoader cl) {
        try {
            Class<?> reqClass = Class.forName(CLS_NLP_REQUEST, false, cl);

            // 尝试 String 构造函数: Request(String query)
            try {
                Constructor<?> ctor = reqClass.getConstructor(String.class);
                Object req = ctor.newInstance(text);
                Logger.d("AiClientHook: Nlp.Request created via String ctor");
                return req;
            } catch (Exception ignored) {}

            // 回退: 无参构造 + setQuery
            Object req = reqClass.newInstance();
            try {
                Method setQuery = reqClass.getMethod("setQuery", String.class);
                setQuery.invoke(req, text);
                Logger.d("AiClientHook: Nlp.Request created via setQuery");
                return req;
            } catch (Exception ignored) {}

            // 最后回退: 直接设置字段
            Field qField = findField(reqClass, "query");
            if (qField != null) {
                qField.setAccessible(true);
                qField.set(req, text);
                Logger.d("AiClientHook: Nlp.Request created via field");
                return req;
            }

            Logger.d("AiClientHook: cannot set query on Nlp.Request");
            return null;

        } catch (ClassNotFoundException e) {
            Logger.d("AiClientHook: Nlp.Request class not found: " + CLS_NLP_REQUEST);
            return null;
        } catch (Exception e) {
            Logger.d("AiClientHook: createNlpRequest failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 通过 APIUtils.buildEvent(EventPayload) 构造 Event
     * 这是语音助手的标准方式, 正确处理 @NamespaceName 注解
     */
    private static Object buildEventViaApiUtils(Object payload, ClassLoader cl) {
        try {
            Class<?> apiUtilsClass = Class.forName(CLS_API_UTILS, false, cl);
            Class<?> eventPayloadClass = Class.forName(CLS_EVENT_PAYLOAD, false, cl);

            // APIUtils.buildEvent(EventPayload) - 单参数版本
            Method buildEvent = null;
            for (Method m : apiUtilsClass.getDeclaredMethods()) {
                if (m.getName().equals("buildEvent") && m.getParameterCount() == 1) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType.isAssignableFrom(payload.getClass()) || paramType == eventPayloadClass) {
                        buildEvent = m;
                        break;
                    }
                }
            }

            if (buildEvent == null) {
                // 尝试直接 getMethod
                try {
                    buildEvent = apiUtilsClass.getDeclaredMethod("buildEvent", eventPayloadClass);
                } catch (NoSuchMethodException ignored) {}
            }

            if (buildEvent != null) {
                buildEvent.setAccessible(true);
                Object event = buildEvent.invoke(null, payload);
                Logger.d("AiClientHook: Event built via APIUtils.buildEvent");
                return event;
            }

            Logger.d("AiClientHook: APIUtils.buildEvent(EventPayload) not found");
            return null;

        } catch (ClassNotFoundException e) {
            Logger.d("AiClientHook: APIUtils class not found: " + CLS_API_UTILS);
            return null;
        } catch (Exception e) {
            Logger.d("AiClientHook: buildEventViaApiUtils failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 手动构造 Event (回退方案)
     * Event = new Event(new EventHeader("Nlp", "Request").setId(randomId), request)
     */
    private static Object buildEventManual(Object payload, ClassLoader cl) {
        try {
            Class<?> eventClass = Class.forName(CLS_EVENT, false, cl);
            Class<?> ehClass = Class.forName(CLS_EVENT_HEADER, false, cl);

            // 创建 EventHeader("Nlp", "Request")
            Object header = null;
            try {
                Constructor<?> ehCtor = ehClass.getConstructor(String.class, String.class);
                header = ehCtor.newInstance("Nlp", "Request");
            } catch (Exception e) {
                Logger.d("AiClientHook: EventHeader(String,String) ctor failed: " + e.getMessage());
                header = ehClass.newInstance();
                callSetter(header, ehClass, "setName", "Request");
                callSetter(header, ehClass, "setNamespace", "Nlp");
            }

            // 设置 ID
            String eventId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            try {
                Method setId = ehClass.getMethod("setId", String.class);
                setId.invoke(header, eventId);
            } catch (Exception ignored) {}
            Logger.d("AiClientHook: manual EventHeader created, id=" + eventId);

            // 创建 Event(header, payload)
            for (Constructor<?> ctor : eventClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 2) {
                    ctor.setAccessible(true);
                    try {
                        Object event = ctor.newInstance(header, payload);
                        Logger.d("AiClientHook: Event created via 2-arg ctor");
                        return event;
                    } catch (Exception e) {
                        Logger.d("AiClientHook: Event(header, payload) ctor failed: " + e.getMessage());
                    }
                }
            }

            // 回退: 无参构造 + setHeader + setPayload
            Object event = eventClass.newInstance();
            invokeSetter(event, "setHeader", header);
            invokeSetter(event, "setPayload", payload);
            Logger.d("AiClientHook: Event created via setters");
            return event;

        } catch (Exception e) {
            Logger.d("AiClientHook: buildEventManual failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 调用 Channel.postEvent(Event) — 方法名混淆, 按参数类型搜索
     * 只搜索 Channel 类及其父类 (排除 Object 类)
     * v3.6: 过滤掉 Object 参数的方法 (identityHashCode 等), 优先匹配 Event 类型参数
     */
    private static boolean callPostEvent(Object channel, Object event) {
        try {
            Class<?> eventClass = event.getClass();
            Class<?> cls = channel.getClass();

            // 先尝试精确匹配: 参数类型为 Event 或其父类 (非 Object)
            while (cls != null && cls != Object.class) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    if (m.getParameterCount() != 1) continue;

                    Class<?> paramType = m.getParameterTypes()[0];
                    // 跳过太通用的参数类型 (Object, Serializable 等)
                    if (paramType == Object.class || paramType == java.io.Serializable.class) continue;
                    // 跳过明显不是事件的方法
                    String mn = m.getName();
                    if (mn.equals("hashCode") || mn.equals("equals") || mn.equals("toString")
                        || mn.equals("identityHashCode") || mn.startsWith("access$")) continue;

                    if (paramType.isAssignableFrom(eventClass)) {
                        m.setAccessible(true);
                        try {
                            Object result = m.invoke(channel, event);
                            Logger.d("AiClientHook: postEvent via " + cls.getSimpleName() + "." + m.getName()
                                + "(" + paramType.getSimpleName() + ") result=" + result);
                            if (result instanceof Boolean) {
                                return (Boolean) result;
                            }
                            return true;
                        } catch (Exception e) {
                            Logger.d("AiClientHook: " + m.getName() + " invoke failed: " + e.getMessage());
                        }
                    }
                }
                cls = cls.getSuperclass();
            }

            Logger.d("AiClientHook: no single-arg method accepting Event on " + channel.getClass().getName());
        } catch (Exception e) {
            Logger.e("AiClientHook: callPostEvent failed: " + e.getMessage());
        }
        return false;
    }

    /** 通过 Intent 发送文本查询 (回退方案) */
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

    // === 工具方法 ===

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 按返回类型和参数数量查找方法 (方法名可能混淆) */
    private static Method findMethodBySignature(Class<?> cls, Class<?> returnType, int paramCount) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() == returnType && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

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

    private static void callSetter(Object obj, Class<?> cls, String methodName, String value) {
        try {
            Method m = cls.getMethod(methodName, String.class);
            m.setAccessible(true);
            m.invoke(obj, value);
        } catch (Exception ignored) {}
    }

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
