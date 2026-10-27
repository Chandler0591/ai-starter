<!--
  评测回归页（仅 ADMIN）：一键跑离线回归集，逐条看关键词命中情况。
  后端只留最近一份报告在内存里，无历史曲线；要长期留存需接 CI（--eval=true 启动即评测）。

  @author Flynn
  @date 2026-10-27
-->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { lastEvalReport, runEval } from '@/api/dashboard'
import type { EvalCaseResult, EvalReport } from '@/api/types'

/** 展示中的报告；null 表示尚无评测记录 */
const report = ref<EvalReport | null>(null)
/** 评测同步执行，期间禁用按钮避免叠加请求 */
const running = ref(false)

/** 通过率百分比，无报告或用例数为 0 时回 0（避免 NaN） */
const passRate = computed(() => {
  if (!report.value || !report.value.total) return 0
  return Math.round((report.value.passed / report.value.total) * 100)
})

/** 取服务内存里最近一次报告；后端重启后为空，拉失败也归为 null，统一走“还没有评测记录”空态 */
async function loadLast() {
  try {
    report.value = (await lastEvalReport()) ?? null
  } catch {
    report.value = null
  }
}

/** 跑评测：成功与否都回到报告上展示，全过/有失败分别用 success/warning 提示 */
async function run() {
  running.value = true
  try {
    report.value = await runEval()
    if (report.value.passed === report.value.total) {
      ElMessage.success(`全部通过 ${report.value.passed}/${report.value.total}`)
    } else {
      ElMessage.warning(`有 ${report.value.total - report.value.passed} 条用例失败，请检查 Prompt / 检索参数`)
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    running.value = false
  }
}

// 进页先试拉最近报告；拉取失败保持空态，不弹错误提示
onMounted(loadLast)
</script>

<template>
  <div class="page">
    <h2 class="page-title">评测回归</h2>
    <p class="page-desc">
      改 Prompt、换模型、调 top-k / 阈值之后先跑一遍。断言用「包含关键词」而非全文比对，避免措辞变化导致脆断。
      CI 里可用 <span class="mono">--eval=true</span> 启动即评测，未全通过会让启动失败。
    </p>

    <div class="bar">
      <el-button type="primary" :loading="running" @click="run">运行评测集</el-button>
      <el-button @click="loadLast">重新载入最近报告</el-button>
      <template v-if="report">
        <el-tag :type="report.passed === report.total ? 'success' : 'danger'" class="ml">
          {{ report.passed }} / {{ report.total }} 通过（{{ passRate }}%）
        </el-tag>
        <span class="mono dim">provider={{ report.provider }} · {{ new Date(report.ranAt).toLocaleString() }}</span>
      </template>
      <span v-else class="dim">还没有评测记录</span>
    </div>

    <el-table v-if="report" :data="report.results" size="small" class="table">
      <el-table-column prop="id" label="用例" width="120" />
      <el-table-column prop="input" label="输入" min-width="200" show-overflow-tooltip />
      <el-table-column label="期望包含" min-width="180">
        <template #default="{ row }: { row: EvalCaseResult }">
          <el-tag v-for="token in row.expectContains" :key="token" size="small" class="mr4">{{ token }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="actual" label="实际输出" min-width="260" show-overflow-tooltip />
      <el-table-column label="结果" width="90">
        <template #default="{ row }: { row: EvalCaseResult }">
          <el-tag :type="row.passed ? 'success' : 'danger'" size="small">
            {{ row.passed ? 'PASS' : 'FAIL' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.ml {
  margin-left: 8px;
}

.mr4 {
  margin-right: 4px;
}

.dim {
  color: #909399;
  font-size: 12px;
}

.table {
  background: #fff;
}
</style>
