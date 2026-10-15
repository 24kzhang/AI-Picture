/** Reject delayed snapshots and fence local editing after a lease changes. */
export interface EditingLease { userId: string | null; sessionId: string | null; epoch: number; until: string | null }
export function leaseFromProject(project: any): EditingLease {
  return { userId: project.lease_user_id, sessionId: project.lease_session, epoch: Number(project.lease_epoch), until: project.lease_until }
}
export class CollaborationGate {
  epoch = -1
  serverTime = -1
  accept(remote: EditingLease, serverTime: number): boolean {
    if (remote.epoch < this.epoch || remote.epoch === this.epoch && serverTime < this.serverTime) return false
    this.epoch = remote.epoch; this.serverTime = serverTime
    return true
  }
  reconcile(local: EditingLease | undefined, remote: EditingLease, sessionId: string, serverTime: number): EditingLease | undefined {
    if (!this.accept(remote, serverTime)) return local
    if (!local || remote.epoch !== local.epoch || remote.sessionId !== sessionId || remote.userId !== local.userId || !remote.until || Date.parse(remote.until) <= serverTime) return undefined
    return remote
  }
}
export function invalidatesEditing(error: unknown): boolean {
  const failure = error as { code?: string; status?: number }
  return ['LEASE_EXPIRED', 'LEASE_REQUIRED'].includes(failure.code || '') || failure.status === 401 || failure.status === 403
}
