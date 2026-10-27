<!--
  主框架布局：左侧导航 + 顶栏身份区 + 内容出口。
  登录页不用本布局（它在路由里是平级页），因此本组件默认已登录。

  @author Flynn
  @date 2026-10-27
-->
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

/** 导航项：name 对应路由 name，icon 是全局注册过的 Element Plus 图标组件名 */
interface Menu {
  name: string
  title: string
  icon: string
  /** 仅 ADMIN 可见 */
  admin?: boolean
}

// 菜单写在本组件而不从路由 meta 生成：页面就这四个，直接枚举比扫 routes 更直观
const MENUS: Menu[] = [
  { name: 'chat', title: '智能对话', icon: 'ChatDotRound' },
  { name: 'knowledge', title: '知识库', icon: 'Collection' },
  { name: 'dashboard', title: '运行看板', icon: 'DataLine', admin: true },
  { name: 'eval', title: '评测回归', icon: 'Aim', admin: true }
]

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 当前路径，直接用作 el-menu 的 active 索引（菜单 index 就是 `/${name}`） */
const activeMenu = computed(() => route.path)
/** 普通用户过滤掉 admin 页；后端仍有接口级鉴权，这里只是不暴露入口 */
const visibleMenus = computed(() => MENUS.filter((m) => !m.admin || auth.isAdmin))

/** 退出：先二次确认，确认后只清本地登录态（后端 JWT 无状态，登出不需要服务端接口） */
async function logout() {
  try {
    await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' })
  } catch {
    return
  }
  auth.logout()
  ElMessage.success('已退出')
  router.replace('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">
        <el-icon :size="20"><Cpu /></el-icon>
        <span>AI 工程化控制台</span>
      </div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item v-for="m in visibleMenus" :key="m.name" :index="`/${m.name}`">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header height="52px" class="header">
        <div class="spacer" />
        <el-tag :type="auth.isAdmin ? 'danger' : 'info'" size="small" effect="plain">
          {{ auth.isAdmin ? 'ADMIN' : 'USER' }}
        </el-tag>
        <span class="user">{{ auth.username }}</span>
        <el-button text size="small" @click="logout">退出</el-button>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background: #fff;
  border-right: 1px solid #e4e7ed;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 52px;
  padding: 0 16px;
  font-weight: 600;
  border-bottom: 1px solid #e4e7ed;
}

.menu {
  border-right: none;
}

.header {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.spacer {
  flex: 1;
}

.user {
  font-size: 13px;
  color: #606266;
}

.main {
  padding: 0;
  overflow-y: auto;
}
</style>
