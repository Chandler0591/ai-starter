/**
 * 路由表与导航守卫：登录态检查 + 管理员页面拦截 + 401 回登录页。
 * 页面全部懒加载（按路由分包）；主框架页在父级 layout，子页只填内容区。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { setUnauthorizedHandler } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由元信息约定：
 * - meta.public：免登录可进（仅登录页）
 * - meta.admin：需要 ADMIN 角色（看板、评测）
 * - meta.title：页面名称文案；当前无消费方（导航与页头文字在各组件里写死），留作扩展点
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    children: [
      { path: '', redirect: '/chat' },
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '智能对话' }
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('@/views/KnowledgeView.vue'),
        meta: { title: '知识库' }
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '运行看板', admin: true }
      },
      {
        path: 'eval',
        name: 'eval',
        component: () => import('@/views/EvalView.vue'),
        meta: { title: '评测回归', admin: true }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
]

/** HTML5 history 模式（无 # 号），部署时需由 Nginx 把未命中的路径回落到 index.html */
const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 导航守卫：路由级权限只是体验优化，真正的鉴权在后端 SecurityConfig（前端不可信）。
 * 只拦两种情况：完全未登录（带 redirect 去登录页）、普通用户进管理员页（回落对话页）。
 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true
  if (!auth.isAuthenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.admin && !auth.isAdmin) return { path: '/chat' }
  return true
})

/**
 * 令牌失效时的统一处置：清身份并回到登录页，保留当前路径以便登录后跳回。
 * 写在路由模块而不是 http 模块，是为了保持 http 不反向依赖 router（http 只持有回调）。
 */
setUnauthorizedHandler(() => {
  const auth = useAuthStore()
  auth.logout()
  void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})

// main.ts 直接 app.use(router) 即可，守卫与 401 回调已在模块加载时生效
export default router
