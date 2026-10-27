<!--
  登录页：账号密码登录 + 演示账号一键填入。
  登录成功后优先回到 redirect 参数指定的页面，没带参数时按角色落地（ADMIN 看看板，USER 进对话）。

  @author Flynn
  @date 2026-10-27
-->
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const formRef = ref<FormInstance>()
/** 提交中标记，兼作按钮 loading，顺便挡住重复提交 */
const loading = ref(false)
// 默认填上演示账号只为本地跑起来省事，真实部署请清空这两个初值
const form = reactive({ username: 'admin', password: 'admin123' })

/** 前端必填校验只拦空输入，真正的账号口令校验在后端 */
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

/** 演示账号快捷填入，不自动提交（避免误触） */
function fill(username: string, password: string) {
  form.username = username
  form.password = password
}

/**
 * 提交登录：先走表单校验，再调 store。
 * 失败（账号口令错、服务不可用）只弹后端可读文案，不区分“用户不存在”与“密码错”。
 */
async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const res = await auth.login(form.username, form.password)
    ElMessage.success(`欢迎，${res.username}`)
    const redirect = (route.query.redirect as string) ?? (res.role === 'ROLE_ADMIN' ? '/dashboard' : '/chat')
    router.replace(redirect)
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <el-card class="card" shadow="always">
      <h1 class="title">AI 工程化控制台</h1>
      <p class="subtitle">Spring Boot 4 + Spring AI 2 · RAG / 工具调用 / 护栏 / 评测 / 可观测</p>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            size="large"
            show-password
            placeholder="密码"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>

      <el-divider>演示账号（生产请改密并启用 HTTPS）</el-divider>
      <div class="accounts">
        <el-button text size="small" @click="fill('admin', 'admin123')">admin / admin123（管理员）</el-button>
        <el-button text size="small" @click="fill('user', 'user123')">user / user123（普通用户）</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: linear-gradient(135deg, #1f2d3d, #3a6ea5);
}

.card {
  width: 400px;
  padding: 8px 12px 16px;
}

.title {
  margin: 0;
  font-size: 20px;
}

.subtitle {
  margin: 6px 0 20px;
  font-size: 12px;
  color: #909399;
}

.submit {
  width: 100%;
}

.accounts {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.accounts .el-button {
  margin-left: 0;
}
</style>
