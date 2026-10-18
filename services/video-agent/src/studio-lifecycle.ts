export type StudioActivity = {
  lastUsedAt: number
  activeRequests: number
  activeSockets: number
}

export function isStudioIdle(activity: StudioActivity, now: number, ttlMs: number) {
  return activity.activeRequests === 0 && activity.activeSockets === 0 && now - activity.lastUsedAt >= ttlMs
}
