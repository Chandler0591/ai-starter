/**
 * 登录与当前用户接口。
 * 本模块只负责发请求并返回结果，是否写入本地登录态由调用方（store）决定，避免这里产生副作用。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { request } from './http'
import type { LoginResponse, MeResponse } from './types'

/**
 * 账号密码登录。auth=false 不携带旧 token；失败（401）抛 ApiError，由页面展示后端可读文案。
 *
 * @param username 登录名
 * @param password 密码只在本次请求体里发送，前端不做任何本地缓存
 */
export function login(username: string, password: string) {
  return request<LoginResponse>('/api/auth/login', {
    method: 'POST',
    auth: false,
    body: { username, password }
  })
}

/** 取当前登录者身份（含角色），用于刷新页面后恢复权限与菜单 */
export function me() {
  return request<MeResponse>('/api/auth/me')
}
