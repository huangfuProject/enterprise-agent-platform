import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
// 开发代理让浏览器统一同源访问；生产环境的路由和认证仍需部署层配置。
// /actuator 仅用于 P0 连接检查，不把前端健康提示当作权限或执行成功证明。
export default defineConfig({ plugins: [vue()], server: { proxy: { '/api': 'http://127.0.0.1:8080', '/actuator': 'http://127.0.0.1:8080' } } })
