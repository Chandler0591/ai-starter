/**
 * 应用入口：拼装 Vue 实例、Pinia、路由与 Element Plus。
 * 插件注册顺序有要求：router 依赖 pinia 里的登录态（导航守卫读 useAuthStore），故 pinia 先装。
 *
 * @author Flynn
 * @date 2026-10-27
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as icons from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
// 全局样式入口（变量与基础布局），组件自有样式写在 SFC 的 scoped style 里
import './styles/index.css'

const app = createApp(App)

// 图标全局注册，模板里直接 <el-icon><ChatDotRound /></el-icon>；代价是打包时图标全量引入
for (const [name, component] of Object.entries(icons)) {
  app.component(name, component)
}

/** 装载插件：pinia 给 store，router 给导航，Element Plus 指定中文文案 */
app.use(createPinia()).use(router).use(ElementPlus, { locale: zhCn })
app.mount('#app')
