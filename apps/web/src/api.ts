/**
 * 查询平台健康状态；所有页面通过此 API 边界访问后端。
 * @param signal 调用方的取消/超时信号；未提供时采用五秒时限。
 * @returns 后端健康状态；非 2xx 抛出适合界面展示的有限错误。
 */
export async function getHealth(signal?: AbortSignal): Promise<string> {
  const response = await fetch('/actuator/health', { signal: signal ?? AbortSignal.timeout(5000) })
  if (!response.ok) throw new Error(`服务暂不可用 (${response.status})`)
  // 类型声明约束页面使用方式；生产接入可另加响应 Schema 校验。
  const data: { status: string } = await response.json()
  return data.status
}
