<!--
  知识库页：文档列表 + 文本录入 + 文件上传 + 重建索引 + 删除。
  读接口普通用户也能用，写操作按 auth.isAdmin 收起入口（后端同样有接口级鉴权）。

  @author Flynn
  @date 2026-10-27
-->
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteDoc, listDocs, reindexDoc, uploadFile, uploadText } from '@/api/knowledge'
import type { DocView, IndexStatus } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const docs = ref<DocView[]>([])
/** 列表拉取中，接 el-table / 刷新按钮的 loading */
const loading = ref(false)
const dialogVisible = ref(false)
/** 录入提交中：后端同步做切片+向量化，回来之前挡住重复提交 */
const submitting = ref(false)
/** 录入表单；source 固定 MANUAL，文件上传走另一条路（后端写 FILE） */
const form = reactive({ title: '', content: '', source: 'MANUAL' })

/** 索引状态 -> 标签颜色：FAILED 标红提醒去 reindex 重试 */
const STATUS_TAG: Record<IndexStatus, 'info' | 'success' | 'danger'> = {
  PENDING: 'info',
  INDEXED: 'success',
  FAILED: 'danger'
}

/** 重拉列表（录入/上传/重建/删除后都调它，以后端回写的 chunkCount 为准） */
async function load() {
  loading.value = true
  try {
    docs.value = await listDocs()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}

/** 打开录入弹窗；每次重置表单，避免上一轮未提交的长正文残留 */
function openDialog() {
  form.title = ''
  form.content = ''
  form.source = 'MANUAL'
  dialogVisible.value = true
}

/**
 * 提交手工录入：前端只拦空标题/空正文，后端另有 @NotBlank 与标题 200 字上限。
 * 向量化环节失败时后端不报错，而是回 status=FAILED（列表里标红），可用「重建索引」重试。
 */
async function submitText() {
  if (!form.title.trim() || !form.content.trim()) {
    ElMessage.warning('标题与内容都不能为空')
    return
  }
  submitting.value = true
  try {
    const doc = await uploadText({ ...form })
    ElMessage.success(`已入库并切片向量化：${doc.chunkCount} 个片段`)
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    submitting.value = false
  }
}

/** el-upload 自定义上传：后端只演示 .txt/.md，PDF/DOCX 在 KnowledgeController 处扩展 */
async function customUpload(option: { file: File }) {
  try {
    const doc = await uploadFile(option.file)
    ElMessage.success(`${doc.title}：${doc.chunkCount} 个片段`)
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}

/** 重建索引（ADMIN）。失败时后端抛错，本函数未包 try，错误会落在控制台——演示可接受，生产需补提醒 */
async function reindex(doc: DocView) {
  const updated = await reindexDoc(doc.id)
  ElMessage.success(`重建索引完成：${updated.chunkCount} 个片段`)
  await load()
}

/** 删除文档（ADMIN）：连带清向量，避免已删文档还能被检索到 */
async function remove(doc: DocView) {
  try {
    await ElMessageBox.confirm(`删除「${doc.title}」及其向量片段？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await deleteDoc(doc.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <h2 class="page-title">知识库</h2>
    <p class="page-desc">
      文档入库即「切片 → 向量化 → 写 pgvector」，对话时按相似度检索并按阈值降级；换 embedding 模型请重建索引。
    </p>

    <div class="bar">
      <el-button :loading="loading" @click="load">刷新</el-button>
      <template v-if="auth.isAdmin">
        <el-button type="primary" @click="openDialog">
          <el-icon><Plus /></el-icon>&nbsp;录入文本
        </el-button>
        <el-upload :show-file-list="false" accept=".txt,.md" :http-request="customUpload">
          <el-button>
            <el-icon><Upload /></el-icon>&nbsp;上传 .txt/.md
          </el-button>
        </el-upload>
      </template>
      <el-tag v-else size="small" type="info" effect="plain">普通用户只读（写操作需 ADMIN）</el-tag>
    </div>

    <el-table :data="docs" v-loading="loading" size="small" class="table">
      <el-table-column prop="id" label="#" width="60" />
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="source" label="来源" width="100" />
      <el-table-column prop="chunkCount" label="片段数" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }: { row: DocView }">
          <el-tag :type="STATUS_TAG[row.status]" size="small" effect="light">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }: { row: DocView }">
          {{ new Date(row.createdAt).toLocaleString() }}
        </template>
      </el-table-column>
      <el-table-column v-if="auth.isAdmin" label="操作" width="160" fixed="right">
        <template #default="{ row }: { row: DocView }">
          <el-button text size="small" @click="reindex(row)">重建索引</el-button>
          <el-button text size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty-hint">还没有文档，先录入一条制度/FAQ 再试对话检索</div>
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" title="录入知识文本" width="640px">
      <el-form label-position="top">
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="200" placeholder="例如：员工福利制度" />
        </el-form-item>
        <el-form-item label="来源标识">
          <el-input v-model="form.source" placeholder="MANUAL / FILE / 系统名" />
        </el-form-item>
        <el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="10"
            placeholder="写成条目化、含关键词的正文，检索效果更好"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitText">入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.table {
  background: #fff;
}
</style>
