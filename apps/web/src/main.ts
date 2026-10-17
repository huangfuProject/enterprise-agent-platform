/** Vue 最小启动入口；只挂载应用，不在模块顶层触发后端业务请求。 */
import { createApp } from 'vue'
import App from './App.vue'
createApp(App).mount('#app')
