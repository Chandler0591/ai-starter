/**
 * 知识库接口：列表对所有登录用户开放，录入 / 重建 / 删除仅 ADMIN（规则在后端 SecurityConfig）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { request, upload } from './http'
import type { DocView, UploadRequest } from './types'

/** 文档列表：只返元数据（标题/来源/片段数/索引状态），不含正文 */
export function listDocs() {
  return request<DocView[]>('/api/knowledge')
}

/** 手工录入文本（ADMIN），后端同步切片向量化，失败时返回 status=FAILED */
export function uploadText(payload: UploadRequest) {
  return request<DocView>('/api/knowledge', { method: 'POST', body: payload })
}

/** 文件上传（ADMIN）：multipart 字段名固定为 file，标题取原文件名；当前只按 UTF-8 读 .txt/.md */
export function uploadFile(file: File) {
  return upload<DocView>('/api/knowledge/file', file)
}

/** 重新索引（ADMIN）：换 embedding 模型或改切片参数后使用，先删旧向量再写入，重复调用不产生重复片段 */
export function reindexDoc(id: number) {
  return request<DocView>(`/api/knowledge/${id}/reindex`, { method: 'POST' })
}

/** 删除文档（ADMIN）：业务记录与向量一起清理，避免残留向量继续被召回 */
export function deleteDoc(id: number) {
  return request<void>(`/api/knowledge/${id}`, { method: 'DELETE' })
}
