<!--
  运行看板（仅 ADMIN）：直读后端 MeterRegistry 里的 ai.* 指标，5 秒轮询。
  只展示瞬时累计值，不含历史趋势（趋势交给 Prometheus + Grafana）。

  @author Flynn
  @date 2026-10-27
-->
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchMetrics } from '@/api/dashboard'
import type { DashboardView, MetricRow } from '@/api/types'

/** 看板快照；null 表示首次拉取还没成功 */
const data = ref<DashboardView | null>(null)
const loading = ref(false)
/** 自动刷新开关，默认开（演示时指标能自己动） */
const auto = ref(true)
/** 轮询定时器 id，undefined 表示未在轮询；离开页面必须清除 */
let timer: number | undefined

/** 拉一次快照；失败只弹错，不清旧数据，避免一次抖动把看板刷空 */
async function load() {
  loading.value = true
  try {
    data.value = await fetchMetrics()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

/** 同名指标可能带不同 tag（provider/status），按 name 分组后原样展示维度 */
const grouped = computed(() => {
  const map = new Map<string, MetricRow[]>()
  for (const row of data.value?.metrics ?? []) {
    const list = map.get(row.name) ?? []
    list.push(row)
    map.set(row.name, list)
  }
  return [...map.entries()]
})

/** tag 对象拍平成 k=v 阅读串，无 tag 时回一个长横线占位 */
function fmtTags(tags: Record<string, string>) {
  const entries = Object.entries(tags)
  return entries.length ? entries.map(([k, v]) => `${k}=${v}`).join(', ') : '—'
}

/** 指标值格式化：整数直出，小数留 3 位（延迟类均值常见小数） */
function fmtValue(v: number) {
  return Number.isInteger(v) ? String(v) : v.toFixed(3)
}

/** 开/关 5 秒轮询；只给 el-switch 的 change 调，避免重复注册定时器 */
function toggleAuto(on: boolean) {
  if (on) {
    timer = window.setInterval(load, 5000)
  } else if (timer) {
    clearInterval(timer)
    timer = undefined
  }
}

// 进页拉一次并按开关状态起轮询
onMounted(() => {
  load()
  toggleAuto(auto.value)
})
// 组件卸载时必须 clear，否则页面走了还在后台轮询接口
onBeforeUnmount(() => timer && clearInterval(timer))
</script>

<template>
  <div class="page">
    <h2 class="page-title">运行看板</h2>
    <p class="page-desc">
      直接读后端 MeterRegistry 里的 <span class="mono">ai.*</span> 指标，无需接入 Prometheus 即可自查；
      趋势图请交给 Prometheus + Grafana（<span class="mono">/actuator/prometheus</span>）。
    </p>

    <div class="bar">
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-switch v-model="auto" active-text="5 秒自动刷新" @change="toggleAuto" />
    </div>

    <el-row :gutter="12" class="cards">
      <el-col :span="6">
        <el-card shadow="never">
          <div class="k">当前 Provider</div>
          <div class="v">{{ data?.provider ?? '—' }}</div>
          <div class="s">{{ data?.provider === 'mock' ? '离线可跑通全链路' : '真实模型' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="k">知识文档</div>
          <div class="v">{{ data?.knowledgeDocs ?? 0 }}</div>
          <div class="s">RAG 检索语料</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="k">Prompt 版本</div>
          <div class="v">{{ Object.keys(data?.prompts ?? {}).length }}</div>
          <div class="s">文件名即版本，可灰度回滚</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="k">AI 指标数</div>
          <div class="v">{{ data?.metrics.length ?? 0 }}</div>
          <div class="s">延迟 / 调用 / token / 降级</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="data" shadow="never" class="block">
      <template #header>Prompt 注册表</template>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item v-for="(versions, name) in data.prompts" :key="name" :label="name">
          <el-tag v-for="v in versions" :key="v" size="small" class="mr4">{{ v }}</el-tag>
          <el-tag size="small" type="success" effect="plain">latest</el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-for="[name, rows] in grouped" :key="name" shadow="never" class="block">
      <template #header>
        <span class="mono">{{ name }}</span>
      </template>
      <el-table :data="rows" size="small">
        <el-table-column label="维度">
          <template #default="{ row }: { row: MetricRow }">
            <span class="mono">{{ fmtTags(row.tags) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          v-for="key in Object.keys(rows[0]?.values ?? {})"
          :key="key"
          :label="key"
          width="140"
        >
          <template #default="{ row }: { row: MetricRow }">
            {{ fmtValue(row.values[key] ?? 0) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <div v-if="data && !data.metrics.length" class="empty-hint">
      还没有 AI 指标，去「智能对话」发一条消息即可产生
    </div>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
}

.cards .el-card {
  text-align: center;
}

.k {
  font-size: 12px;
  color: #909399;
}

.v {
  margin: 6px 0;
  font-size: 22px;
  font-weight: 600;
}

.s {
  font-size: 11px;
  color: #c0c4cc;
}

.block {
  margin-top: 12px;
}

.mr4 {
  margin-right: 4px;
}
</style>
