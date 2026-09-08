# Feature Specification: 对话直贴图（多模态 · 智能对话切片）

**Feature**: `003-chat-image` | **Created**: 2026-09-08 | **Branch**: main | **Impl**: commits `4d2eeeb`(后端) + `e4fae65`(前端) | **Data Model**: 无（图只转述成文本，落既有会话 history）

**Input**: 知识库收图（切片 1，`specs/002-image-rag/`）后，用户在**聊天对话里**直接贴图（选文件或 Ctrl+V）→ agent 当场看懂图，能答图内内容，且**照常调 RAG 检索 / 认领 / 抑制工具**。

## 一句话设计

**图不是给第二个模型看的，是"翻译"成文本喂给现有文本 agent 的。** 采用用户拍板的**形态 B**：聊天发图 → 复用切片 1 的 `ImageCaptionService`（qwen-vl）把图转述成文本 → 塞进**现有** ReactAgent 输入与 session history（history 是文本结构，转述进 history 才能跨轮可引用）。`ChatRequest` 加三个**可选**图字段，无图请求行为与旧版完全一致。

## User Scenarios & Testing

### User Story 1 — 值班贴一张监控截图，agent 看图即可认领/应答（Priority: P1，已实现）

值班在聊天里把一张含 `HighCPUUsage` 的告警截图（文件或剪贴板粘贴）发给 agent，附一句「认领这个」——agent 从图转述里读到告警名，照常走工具链 `claimAlert` 完成认领；或直接问「图里 CPU 到多少了」得到图文内容。

**Independent Test**：贴图 + 「认领这个」→ agent 据图转述调 claimAlert 成功。纯手动项（需 DashScope + `agent.claim-tool-enabled=true` + 已 DIAGNOSED 告警）。

**Acceptance Scenarios**:
1. **Given** 一张含文字的截图, **When** 选文件或 Ctrl+V 贴图并发送, **Then** agent 返回与图内内容一致的答案；图转述文本进入该会话 history（下一轮 buildSystemPrompt 会带上，可继续追问图内容）。
2. **Given** 贴图 + 文字「认领这个」, **When** 发送, **Then** agent 读图转述后照常调 `claimAlert`（形态 B 的核心价值：图片不切断文本 agent 的工具能力）。
3. **Given** 无图纯文本老请求（body 无 image* 字段）, **When** 发送, **Then** 行为与改造前逐字一致（零行为差，向后兼容）。
4. **Given** 仅图、无文字问题, **When** 发送（question 空但 imageBase64 非空）, **Then** 不拒，agent 收到转述文本（空问题守卫放宽为「question 与 imageBase64 皆空才拒」）。
5. **Given** 非法输入（坏 base64 / mime 越界 / 解码后 >4MB）, **When** 发送, **Then** `IllegalArgumentException` → legacy「HTTP 200 包错误」信封（Chat 旧风格不改）。

## Requirements

- **FR-001**: `/api/chat` 与 `/api/chat_stream` 的 body 必须接受**可选** `imageBase64` / `imageMimeType` / `imageFileName`（`@JsonAlias` 兼容 `image_base64` 蛇形与驼峰）；三字段皆缺省时行为与现状一致。
- **FR-002**: `imageBase64` 必须容忍 `data:...;base64,` 前缀（前端剥过、但服务端再兜一层）；mime 白名单限 jpg/jpeg/png/webp；解码后 ≤ 4MB。非法输入抛 `IllegalArgumentException`（由 controller 现有 catch 兜成聊天错误信封）。
- **FR-003**: 有图时必须经 `ImageCaptionService.caption` 转述成文本，且该文本（连同文字问题）必须作为**用户输入进 agent 与 session history**——服务端**只存转述文本、不存图片二进制**（history 是纯文本结构）。
- **FR-004**: 空问题守卫从「question 空拒」放宽为「**question 与 imageBase64 皆空**才拒」，允许纯图消息。
- **FR-005**: 前端 quick 与 stream 两处发送 body 同构携带图字段（共享 `buildChatPayload`，防两处手写漂移）；发送前显示可移除的图片预览 chip；历史气泡不持久回显图（服务端只存转述）。

## 关键决策注记

| 问题 | 决策 | 为什么 |
|---|---|---|
| 形态 A(纯 qwen-vl 看图) vs B(图转述进文本 agent) | **形态 B**（用户拍板） | VL 走独立视觉链路会与 agent 工具调用体系脱节（该框架 VL 工具调用不成熟）；B 复用切片 1 的 caption 服务 + 现有工具链，agent 看完图仍能认领/抑制，主场景最短、风险最低 |
| 转述文本存哪 | 进 session history（文本） | history 结构是 {role,content} 纯文本，`buildSystemPrompt` 拍平进 system prompt——只有把转述作为用户输入写入，下一轮才可引用 |
| 图片二进制去留 | 只解码转述，不进 history/不落库 | 会话历史无二进制载体；存 base64 会撑爆 system prompt 与 6 对滑窗 |
| 插入点 | `executeChat`/`agent.stream` 前算一次 resolved turn | 两个端点共用 `ChatService.resolveUserTurn`（纯逻辑、可单测），agent 本体零改动 |
| 回显 | 前端 chip 即时可见，气泡不持久回显 | 服务端无图可回显；scope 内可接受（用户刚贴的 chip 已自证所见） |

## Out of Scope

- 形态 A（纯 VL 看图问答，不进工具链）。
- 聊天直发**文档**文件（当前只支持贴图；文档上传走 `/api/upload`，见 `specs/004-native-docs/`）。
- base64/图片落库、历史回显图片。
- 图片进 **AIOps 诊断链路**。
- RBAC/账号。
