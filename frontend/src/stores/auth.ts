/**
 * 登录态 store：只存身份与令牌，业务数据各页面自取。
 * 角色只用于菜单/路由展示，权限判定一律以后端为准。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { login as apiLogin, me as apiMe } from '@/api/auth'
import { clearSession, getToken, getStoredUser, saveSession, type StoredUser } from '@/api/http'

/** 只存身份与令牌，业务数据各页面自取；令牌落 localStorage 便于刷新续期 */
export const useAuthStore = defineStore('auth', () => {
  /** 当前 JWT，初始值从 localStorage 恢复（刷新不掉线） */
  const token = ref<string | null>(getToken())
  /** 登录用户快照，仅用于展示与菜单控制 */
  const user = ref<StoredUser | null>(getStoredUser())

  /** 是否已登录：只看 token 存在与否，是否过期由后端判定 */
  const isAuthenticated = computed(() => !!token.value)
  /** 是否管理员，控制看板 / 评测 / 知识库写入入口的可见性 */
  const isAdmin = computed(() => user.value?.role === 'ROLE_ADMIN')
  /** 展示用用户名，未登录时为空串（避开模板里的 null 判断） */
  const username = computed(() => user.value?.username ?? '')

  /**
   * 登录：拿到 token 后同时更新内存态与 localStorage。
   * 失败时后端的可读文案包在 ApiError 里抛出，由登录页展示，本函数不做兜底。
   */
  async function login(usernameInput: string, password: string) {
    const res = await apiLogin(usernameInput, password)
    token.value = res.token
    user.value = { username: res.username, role: res.role }
    saveSession(res.token, user.value)
    return res
  }

  /**
   * 向后端确认真实身份并回写本地快照（后端过期时会 401，由 http 层触发登出）。
   * 当前无调用方，预留给“刷新后校对权限”类场景。
   */
  async function refresh() {
    const info = await apiMe()
    user.value = { username: info.username, role: info.role }
    saveSession(token.value ?? '', user.value)
    return info
  }

  /** 登出：内存与本地存储一起清，不留可被误用的旧 token */
  function logout() {
    token.value = null
    user.value = null
    clearSession()
  }

  return { token, user, isAuthenticated, isAdmin, username, login, refresh, logout }
})
