<!--
  对话页：左侧会话列表 + 右侧流式问答。
  本页是 AI 能力的集中体现：气泡下方展 traceId（可直接捞日志）、回答来源（provider）、
  护栏拦截/降级时气泡标红、可切换 Prompt 版本与工具开关做对照实验。

  @author Flynn
  @date 2026-10-27
-->
<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteSession, listMessages, listSessions, streamChat } from '@/api/chat'
import type { ChatChunk, SessionView } from '@/api/types'

/** 渲染用气泡：历史消息与本轮流式增量都归一到这里，不反向依赖后端 MessageView */
interface Bubble {
  role: 'user' | 'assistant'
  content: string
  traceId?: string
  /** 护栏拦截或降级文案，模板据此标红 */
  error?: boolean
}

const sessions = ref<SessionView[]>([])
/** 当前气泡列表（含尚未落库的本轮回答），不等于服务端历史，刷新后需重选会话才能拉回 */
const messages = ref<Bubble[]>([])
/** null 表示新会话；流式首帧带回 sessionId 后立即回填 */
const sessionId = ref<number | null>(null)
const input = ref('')
/** Prompt 版本，默认 latest，由后端解析为当前注册的最新版 */
const promptVersion = ref('latest')
const useTools = ref(true)
/** 流式进行中：禁用发送/新建/切换会话，避免同时开两路流 */
const streaming = ref(false)
/** 消息容器元素，用于贴底滚动 */
const listRef = ref<HTMLElement>()
/** 当前流的中止控制器，null 表示没有在跑的流 */
let controller: AbortController | null = null

/** 拉会话列表；失败只弹错不阻断页面（空列表比白屏好） */
async function loadSessions() {
  try {
    sessions.value = await listSessions()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/**
 * 打开已有会话：先拉历史再切 sessionId，中途失败时停留在原会话上。
 * 流式进行中拦掉切换，否则两路回答会写进同一个气泡列表。
 * 后端对他人会话返 403，这里不特殊处理，统一弹后端可读文案。
 */
async function openSession(id: number) {
  if (streaming.value) return
  try {
    const history = await listMessages(id)
    sessionId.value = id
    messages.value = history
      .filter((m) => m.role !== 'system')
      .map((m): Bubble => ({
        role: m.role === 'user' ? 'user' : 'assistant',
        content: m.content,
        traceId: m.traceId
      }))
    await scrollToBottom()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/** 新会话：只清本地状态，真正的会话等首次提问由后端创建并回 id */
function newSession() {
  if (streaming.value) return
  sessionId.value = null
  messages.value = []
}

/** 删除会话：确认后调后端，本地列表就地摘除；删的是当前会话则顺便回到新会话 */
async function removeSession(id: number) {
  try {
    await ElMessageBox.confirm('删除该会话及其全部消息？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await deleteSession(id)
  sessions.value = sessions.value.filter((s) => s.id !== id)
  if (sessionId.value === id) newSession()
  ElMessage.success('已删除')
}

/** 滚到底部；先 nextTick 等 DOM 更新完，否则新内容高度还未计入 scrollHeight */
async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

/**
 * 发送一轮提问：先占两个气泡（用户问 + 空回答），再把流式增量追到回答气泡上。
 * 失败不删气泡，而是把已有内容标红，保留“生成到哪一步”的现场。
 * 收尾统一重拉会话列表：新会话此时才拿到 id 与标题。
 */
async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return

  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '' })
  // 取回响应式代理再改写，否则流式追加不会触发重渲染
  const reply = messages.value[messages.value.length - 1]
  input.value = ''
  streaming.value = true
  controller = new AbortController()
  await scrollToBottom()

  try {
    await streamChat(
      {
        message: text,
        sessionId: sessionId.value,
        promptVersion: promptVersion.value,
        useTools: useTools.value
      },
      {
        onChunk: (chunk: ChatChunk) => {
          reply.content += chunk.content
          if (chunk.traceId) reply.traceId = chunk.traceId
          if (chunk.error) reply.error = true
          if (chunk.sessionId && sessionId.value === null) sessionId.value = chunk.sessionId
        }
      },
      controller.signal
    )
  } catch (e) {
    reply.error = true
    reply.content = reply.content || `请求失败：${(e as Error).message}`
    if ((e as Error).name !== 'AbortError') ElMessage.error((e as Error).message)
  } finally {
    streaming.value = false
    controller = null
    await scrollToBottom()
    await loadSessions()
  }
}

/** 手动中止当前流（abort 会抛 AbortError，这里挡掉错提示，已生成的部分保留在气泡里） */
function stop() {
  controller?.abort()
}

// 进页先拉会话列表，但不默认打开任何一个（避免一进页就铺一屏历史）
onMounted(loadSessions)
</script>

<template>
  <div class="chat">
    <!-- 会话侧栏：列出当前用户的会话（ADMIN 可见全部），标题下带 Prompt 版本方便比对 -->
    <aside class="side">
      <el-button type="primary" class="new" :disabled="streaming" @click="newSession">
        <el-icon><Plus /></el-icon>&nbsp;新建对话
      </el-button>
      <el-scrollbar class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session"
          :class="{ active: s.id === sessionId }"
          @click="openSession(s.id)"
        >
          <div class="session-title">{{ s.title }}</div>
          <div class="session-meta">
            <span class="mono">{{ s.promptKey }}@{{ s.promptVer }}</span>
            <el-button text size="small" @click.stop="removeSession(s.id)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>
        <div v-if="!sessions.length" class="empty-hint">还没有会话</div>
      </el-scrollbar>
    </aside>

    <section class="body">
      <!-- 实验开关：Prompt 版本与工具调用都可单轮切换，用于同一问题下对比回答 -->
      <div class="toolbar">
        <el-tooltip content="system-chat 的版本（v1/v2 可用于灰度与 A-B 对比）" placement="bottom">
          <el-select v-model="promptVersion" size="small" class="w120" :disabled="streaming">
            <el-option label="latest" value="latest" />
            <el-option label="v1" value="v1" />
            <el-option label="v2" value="v2" />
          </el-select>
        </el-tooltip>
        <el-tooltip content="开启后按角色下发可调用工具（USER：订单查询；ADMIN 额外：知识检索）" placement="bottom">
          <el-switch v-model="useTools" size="small" active-text="工具调用" :disabled="streaming" />
        </el-tooltip>
        <div class="spacer" />
        <el-tag size="small" type="info" effect="plain">多轮记忆来自服务端会话表</el-tag>
      </div>

      <!-- 气泡区：回答为空且正在流式时先占一个省略号；traceId 只挂有值的气泡上 -->
      <div ref="listRef" class="messages">
        <div v-if="!messages.length" class="empty-hint">
          试试：「我最近的订单状态是什么？」或「公司年假有几天？」
        </div>
        <div v-for="(m, i) in messages" :key="i" class="row" :class="m.role">
          <div class="bubble" :class="{ error: m.error }">
            <span class="text">{{ m.content || (streaming && i === messages.length - 1 ? '…' : '') }}</span>
            <div v-if="m.traceId" class="trace mono">traceId: {{ m.traceId }}</div>
          </div>
        </div>
      </div>

      <!-- 输入区：Enter 发送、Shift+Enter 换行；流式进行中可“停止”保留已生成内容 -->
      <div class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          resize="none"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          @keydown.enter.exact.prevent="send"
        />
        <div class="actions">
          <el-button v-if="streaming" @click="stop">停止</el-button>
          <el-button type="primary" :loading="streaming" :disabled="!input.trim()" @click="send">
            发送
          </el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  height: calc(100vh - 52px);
}

.side {
  width: 240px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
}

.new {
  width: 100%;
}

.session-list {
  flex: 1;
}

.session {
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
}

.session:hover {
  background: #f5f7fa;
}

.session.active {
  background: #ecf5ff;
}

.session-title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #a8abb2;
}

.body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.w120 {
  width: 120px;
}

.spacer {
  flex: 1;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.row {
  display: flex;
  margin-bottom: 12px;
}

.row.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 76%;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #e4e7ed;
}

.row.user .bubble {
  background: #ecf5ff;
  border-color: #d9ecff;
}

.bubble.error {
  background: #fef0f0;
  border-color: #fbc4c4;
}

.text {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.6;
}

.trace {
  margin-top: 6px;
  color: #a8abb2;
}

.composer {
  padding: 12px 16px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
</style>
