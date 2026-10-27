/**
 * 环境类型声明：引入 Vite 客户端类型（import.meta.env 等），并让 TS 认识 *.vue 模块。
 *
 * @author Flynn
 * @date 2026-10-27
 */
/// <reference types="vite/client" />

// SFC 的类型由 vue 插件在构建时生成，这里给出最小声明即可让 .ts 文件正常 import .vue
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
