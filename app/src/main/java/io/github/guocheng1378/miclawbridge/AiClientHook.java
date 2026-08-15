package io.github.guocheng1378.miclawbridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 适配 com.miui.voiceassist (小爱同学旧版) 的通信层
 * 替代原 CliClient (原来通过 LocalSocket 与 com.aios.osbot CLI 通信)
 *
 * 核心机制:
 * 1. getAiClient() - 获取 VoiceService 中的 AbsAiClient 实例
 * 2. chat() - 通过反射调用 sendQueryToMain() 发送文本查询
 * 3. Hook handleInstruction() 拦截AI响应 (在 HookEntry 中注册)
 */
public class AiClientHook {

    /** 流式增量回调 */
    public interface TextSink {
        void onDelta(String text);
    }

    // 从 handleInstruction hook 中捕获的 AiClient 实例
    private static volatile Object capturedAiClient = null;

    /**
     * 由 HookEntry 的 handleInstruction hook 调用, 保存 AiClient 实例 (this)
     */
    public static void setAiClient(Object aiClient) {
        if (capturedAiClient == null && aiClient != null) {
            capturedAiClient = aiClient;
            Logger.d("AiClientHook: captured AiClient instance: " + aiClient.getClass().getName());
        }
    }

    // 响应同步
    private static final Object lock = new Object();
    private static CountDownLatch responseLatch = null;
    private static String lastReply = null;
    private static String lastError = null;
    private static int lastFrames = 0;
    private static TextSink currentSink = null;

    /**
     * 收到AI响应时由 Hook 回调调用
     */
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

    /**
     * 收到错误时由 Hook 回调调用
     */
    public static void onError(String error) {
        synchronized (lock) {
            lastError = error;
            if (responseLatch != null) responseLatch.countDown();
        }
    }

    /**
     * 响应结束时由 Hook 回调调用
     */
    public static void onEnd() {
        synchronized (lock) {
            if (responseLatch != null) responseLatch.countDown();
        }
    }

    /**
     * 获取 AiClient 实例
     * 优先使用从 hook 中捕获的实例, 然后从 VoiceService 中搜索, 最后尝试反射创建
     */
    private static Object getAiClient() {
        // 方式1: 从 handleInstruction hook 捕获的实例 (最可靠)
        if (capturedAiClient != null) {
            return capturedAiClient;
        }

        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread", false, getHostClassLoader());
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            android.app.Application app = (android.app.Application) currentApp.invoke(null);
            if (app == null) return null;

            // 方式2: 从 ActivityThread.mServices 中找 VoiceService, 再提取 AiClient
            try {
                java.lang.reflect.Field mServicesField = atClass.getDeclaredField("mServices");
                mServicesField.setAccessible(true);
                // 获取当前 ActivityThread 实例
                Method currentAt = atClass.getDeclaredMethod("currentActivityThread");
                currentAt.setAccessible(true);
                Object activityThread = currentAt.invoke(null);
                if (activityThread != null) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<android.os.IBinder, android.app.Service> services =
                        (java.util.Map<android.os.IBinder, android.app.Service>) mServicesField.get(activityThread);
                    if (services != null) {
                        for (android.app.Service svc : services.values()) {
                            if (svc.getClass().getName().contains("VoiceService") ||
                                svc.getClass().getName().contains("voiceassist")) {
                                Logger.d("AiClientHook: found service: " + svc.getClass().getName());
                                Object client = findAiClientInObject(svc);
                                if (client != null) {
                                    capturedAiClient = client;
                                    Logger.d("AiClientHook: found AiClient in VoiceService: " + client.getClass().getName());
                                    return client;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Logger.e("AiClientHook: VoiceService search failed: " + e.getMessage());
            }

            // 方式3: 遍历 Application 中的 AiClient 字段
            Object client = findAiClientInObject(app);
            if (client != null) {
                capturedAiClient = client;
                Logger.d("AiClientHook: found AiClient in Application");
                return client;
            }

            // 方式4: 尝试 getInstance() 静态方法
            String[] classNames = {
                "com.xiaomi.ai.conn.basic.AiClient",
                "com.xiaomi.ai.conn.basic.AbsAiClient"
            };
            for (String className : classNames) {
                try {
                    Class<?> cls = Class.forName(className, false, getHostClassLoader());
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getParameterCount() == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                                && m.getReturnType().isAssignableFrom(cls)) {
                            m.setAccessible(true);
                            Object instance = m.invoke(null);
                            if (instance != null) {
                                capturedAiClient = instance;
                                Logger.d("AiClientHook: got instance via " + m.getName());
                                return instance;
                            }
                        }
                    }
                } catch (ClassNotFoundException ignored) {}
            }

            Logger.e("AiClientHook: no AiClient found anywhere");
        } catch (Exception e) {
            Logger.e("AiClientHook: getAiClient failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 在对象及其父类中递归搜索 AiClient 实例
     * 先按字段名/类型名快速匹配, 再 instanceof 检查
     */
    private static Object findAiClientInObject(Object obj) {
        return findAiClientInObject(obj, 0, new java.util.IdentityHashMap<>());
    }

    private static Object findAiClientInObject(Object obj, int depth, java.util.IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 4 || visited.containsKey(obj)) return null;
        visited.put(obj, Boolean.TRUE);

        Class<?> cls = obj.getClass();
        // 先检查自己是否就是 AiClient
        if (isAiClientClass(cls)) return obj;

        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                Class<?> type = f.getType();
                if (type.isPrimitive() || type == String.class || type == Class.class) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (isAiClientClass(val.getClass())) {
                        Logger.d("AiClientHook: FOUND AiClient in " + obj.getClass().getSimpleName() + "." + f.getName());
                        return val;
                    }
                    // 递归搜索复杂对象 (最多4层)
                    if (depth < 4 && !type.isPrimitive()) {
                        Object nested = findAiClientInObject(val, depth + 1, visited);
                        if (nested != null) return nested;
                    }
                } catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static boolean isAiClientClass(Class<?> cls) {
        while (cls != null) {
            String name = cls.getName();
            if (name.contains("AbsAiClient") || name.equals("com.xiaomi.ai.conn.basic.AiClient")) return true;
            cls = cls.getSuperclass();
        }
        return false;
    }

    /**
     * 获取宿主 ClassLoader
     */
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

    private static ClassLoader hostClassLoader = null;

    /**
     * 发送文本查询并等待响应
     * 通过反射调用 AbsAiClient.sendQueryToMain(text)
     */
    public static CliClient.CliResult chat(String text, String chatId, String agentId, CliClient.TextSink sink, Object images) {
        synchronized (lock) {
            lastReply = null;
            lastError = null;
            lastFrames = 0;
            responseLatch = new CountDownLatch(1);
            currentSink = (sink != null) ? sink::onDelta : null;
        }

        try {
            Object aiClient = getAiClient();
            if (aiClient == null) {
                return new CliClient.CliResult("", "AiClient not available", null, 0);
            }

            // 调用 sendQueryToMain
            Class<?> cls = aiClient.getClass();
            Method sendMethod = null;

            // 查找 sendQueryToMain 方法 (可能有不同的签名)
            for (Method m : cls.getMethods()) {
                if ("sendQueryToMain".equals(m.getName())) {
                    sendMethod = m;
                    break;
                }
            }
            // 也检查父类
            if (sendMethod == null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if ("sendQueryToMain".equals(m.getName())) {
                        sendMethod = m;
                        break;
                    }
                }
            }
            // 沿继承链查找
            if (sendMethod == null) {
                Class<?> superClass = cls.getSuperclass();
                while (superClass != null && superClass != Object.class) {
                    for (Method m : superClass.getDeclaredMethods()) {
                        if ("sendQueryToMain".equals(m.getName())) {
                            sendMethod = m;
                            break;
                        }
                    }
                    if (sendMethod != null) break;
                    superClass = superClass.getSuperclass();
                }
            }

            if (sendMethod == null) {
                // 回退: 尝试多种方法名
                String[] fallbackNames = {"sendQueryToMain", "sendQuery", "sendText", "query", "sendMessage", "handleTextQuery"};
                for (String methodName : fallbackNames) {
                    for (Method m : cls.getMethods()) {
                        if (methodName.equals(m.getName()) && m.getParameterCount() >= 1) {
                            Class<?> firstParam = m.getParameterTypes()[0];
                            if (firstParam == String.class || firstParam == CharSequence.class) {
                                sendMethod = m;
                                Logger.d("AiClientHook: found fallback method: " + methodName);
                                break;
                            }
                        }
                    }
                    if (sendMethod != null) break;
                }
            }

            if (sendMethod == null) {
                // 最终回退: 尝试 VoiceService 的方法
                Logger.e("AiClientHook: no suitable send method found, trying VoiceService fallback");
                return tryFallbackChat(aiClient, text, chatId, agentId);
            }

            sendMethod.setAccessible(true);
            Logger.d("AiClientHook: calling sendQueryToMain, text=" + text);

            // 根据参数类型调用
            Class<?>[] paramTypes = sendMethod.getParameterTypes();
            if (paramTypes.length == 1 && paramTypes[0] == String.class) {
                sendMethod.invoke(aiClient, text);
            } else if (paramTypes.length == 2 && paramTypes[0] == String.class) {
                sendMethod.invoke(aiClient, text, chatId != null ? chatId : "");
            } else {
                // 构造 Application.ScheduleSendQuery 对象
                try {
                    Class<?> ssqClass = Class.forName(
                        "com.xiaomi.ai.api.Application$ScheduleSendQuery", false, getHostClassLoader());
                    Object ssq = ssqClass.newInstance();
                    Method setSendQuery = ssqClass.getMethod("setSendQuery", String.class);
                    setSendQuery.invoke(ssq, text);
                    sendMethod.invoke(aiClient, ssq);
                } catch (Exception ex) {
                    // 最后尝试直接传 String
                    sendMethod.invoke(aiClient, text);
                }
            }

            // 等待响应
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

        } catch (Exception e) {
            Logger.e("AiClientHook: chat failed: " + e.getMessage(), e);
            return new CliClient.CliResult("", e.toString(), null, 0);
        }
    }

    /**
     * 回退方案: 通过 Intent 发送查询
     */
    private static CliClient.CliResult tryFallbackChat(Object aiClient, String text, String chatId, String agentId) {
        try {
            // 尝试通过 VoiceService 的 handleQueryFrom 或 sendTextMessage
            Class<?> voiceServiceClass = Class.forName(
                "com.xiaomi.voiceassistant.VoiceService", false, getHostClassLoader());

            // 查找 sendTextMessage 或 sendQuery
            for (Method m : voiceServiceClass.getDeclaredMethods()) {
                String name = m.getName();
                if ("sendTextMessage".equals(name) || "sendQuery".equals(name) || "handleQueryFrom".equals(name)) {
                    m.setAccessible(true);
                    Class<?>[] params = m.getParameterTypes();
                    Logger.d("AiClientHook: fallback using " + name + " with " + params.length + " params");

                    if (params.length == 1 && params[0] == String.class) {
                        m.invoke(aiClient, text);
                    } else if (params.length >= 2) {
                        // 尝试 text + 来源
                        Object[] args = new Object[params.length];
                        args[0] = text;
                        for (int i = 1; i < params.length; i++) {
                            if (params[i] == int.class || params[i] == Integer.class) args[i] = 0;
                            else if (params[i] == boolean.class) args[i] = false;
                            else if (params[i] == String.class) args[i] = "";
                        }
                        m.invoke(aiClient, args);
                    }

                    boolean completed = responseLatch.await(Config.READ_TIMEOUT, TimeUnit.MILLISECONDS);
                    synchronized (lock) {
                        String reply = lastReply != null ? lastReply : "";
                        String error = lastError;
                        if (!completed && error == null) error = "TIMEOUT";
                        return new CliClient.CliResult(reply, error, chatId, lastFrames);
                    }
                }
            }

            return new CliClient.CliResult("", "No suitable method found on VoiceService", null, 0);
        } catch (Exception e) {
            Logger.e("AiClientHook: fallback failed: " + e.getMessage(), e);
            return new CliClient.CliResult("", "Fallback failed: " + e.getMessage(), null, 0);
        }
    }

    /**
     * 发送文本查询 (简化版)
     */
    public static CliClient.CliResult chat(String text, String chatId, String agentId, CliClient.TextSink sink) {
        return chat(text, chatId, agentId, sink, null);
    }

    /**
     * 发送文本查询 (最简版)
     */
    public static CliClient.CliResult chat(String text, String chatId, String agentId) {
        return chat(text, chatId, agentId, null, null);
    }
}
