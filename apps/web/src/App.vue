<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue'
import { getHealth } from './api'
// P0 只展示连接状态；任务执行与真正授权仍由服务端负责。
const status = ref('尚未检查')
const loading = ref(false)
// 页面卸载时尽早取消网络等待，减少无意义的后台工作。
const controller = new AbortController()
onBeforeUnmount(() => controller.abort())
/** 检查 Java 连接，并统一管理加载、错误和取消；finally 保证按钮状态恢复。 */
async function check() {
  loading.value = true
  // 合并组件生命周期取消和五秒时限，任何一个信号触发都会结束等待。
  try { status.value = await getHealth(AbortSignal.any([controller.signal, AbortSignal.timeout(5000)])) }
  catch (error) { status.value = error instanceof Error ? error.message : '连接失败' }
  finally { loading.value = false }
}
</script>
<template>
  <main><p>ENTERPRISE AGENT PLATFORM · P0</p><h1>工程基线与技术验证</h1>
    <p>受控开发环境。当前提供任务 API、Agent Runtime 和自动化验证。</p>
    <button :disabled="loading" @click="check">{{ loading ? '检查中…' : '检查平台连接' }}</button>
    <p role="status">{{ status }}</p><p>任务提交与事件查询方式见项目 README。</p>
  </main>
</template>
<style>
body{margin:0;background:#f3f5f7;color:#172b3a;font:16px/1.7 system-ui,sans-serif}main{max-width:850px;margin:12vh auto;padding:40px;background:white;border:1px solid #dce3e9;border-radius:16px}h1{font-size:34px}button{padding:10px 20px;background:#17656b;color:white;border:0;border-radius:6px;cursor:pointer}button:disabled{opacity:.5}
</style>
