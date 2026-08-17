# XiaoAiBridge

把小米小爱语音助手 (`com.miui.voiceassist`) 的 AI 能力暴露为 **OpenAI 兼容 HTTP API** 的 Xposed 模块（LibXposed API 102）。

## 原理

通过 Hook 小爱语音助手内部的 `cr0.g`（会话管理器），使用 `Nlp.RequestLargeLanguageModelContent` 将文本直接注入 NLP 管线，绕过语音识别（ASR），响应通过 `Template.ToastStream` 流式提取。

```
用户请求 → HTTP Server (127.0.0.1:8787)
         → cr0.g.getInstance().sendEvent(RequestLargeLanguageModelContent)
         → 小爱 NLP 引擎
         → Template.ToastStream 流式响应
         → OpenAI 兼容 JSON / SSE 返回
```

## 功能

- **OpenAI 兼容**：`POST /v1/chat/completions`（支持流式 SSE + 非流式）
- **多模型**：`voiceassist.main` / `voiceassist.chat` / `voiceassist.nlp` / `voiceassist.skill` / `xiaomi.ai`
- **多轮会话**：`user` 字段控制会话上下文
- **管理**：`GET /v1/models` / `GET /health` / 配置热重载
- **零外部依赖**：全部走本地小爱引擎，无需 API Key

## 安装

1. 下载 APK 安装
2. LSPosed（1.9.2+）启用模块，作用域勾选 `com.miui.voiceassist`
3. 重启小爱语音助手
4. 确认 HTTP 服务已启动（`adb logcat -s XiaoAiBridge`）

## 使用

```bash
# Base URL
http://127.0.0.1:8787/v1

# 对话
curl -X POST http://127.0.0.1:8787/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"voiceassist.main","messages":[{"role":"user","content":"你好"}]}'

# 模型列表
curl http://127.0.0.1:8787/v1/models
```

## API 端点

| 端点 | 说明 |
|------|------|
| `POST /v1/chat/completions` | OpenAI 对话（流式/非流式） |
| `GET /v1/models` | 可用模型列表 |
| `GET /health` | 健康检查 |

## 模型

| 模型名 | 说明 |
|--------|------|
| `voiceassist.main` | 默认小爱助手 |
| `voiceassist.chat` | 聊天模式 |
| `voiceassist.nlp` | NLP 直通 |
| `voiceassist.skill` | 技能模式 |
| `xiaomi.ai` | 小米 AI 通用 |

## 配置

模块设置界面支持：
- HTTP 端口（默认 8787）
- API Token 鉴权
- 请求日志

## 安全

- 仅监听 127.0.0.1（本机访问）
- 可选 Token 鉴权

## 开发

```bash
git clone https://github.com/laishouchao/XiaoAiBridge
# Android Studio 打开，编译即可
```

## 版本历史

- **v5.0.0** (2026-08): 修复通信链路，使用 `Nlp.RequestLargeLanguageModelContent` + `cr0.g.sendEvent()` 实现正确文本注入，API 完全可用
- **v4.x**: 探索 Channel/ChannelListener 通信路径
- **v3.x**: 响应解析修复（ToastStream markdown_text 提取）
- **v2.0**: HTTP Server + OpenAI 兼容 API
- **v1.0**: 初始版本

## 许可

MIT License