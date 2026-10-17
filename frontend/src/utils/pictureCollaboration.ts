interface SharedSnapshot {
  id: string | number
  revision: number
  collaboration?: { epoch: number | string; serverTime: number | string }
}
/** Late responses must never revive an old draft or a previous editing lease. */
export function acceptsPictureSnapshot(current: SharedSnapshot | null | undefined, next: SharedSnapshot): boolean {
  if (!current?.collaboration || !next.collaboration) return true
  if (String(current.id) !== String(next.id)) {
    try { return BigInt(next.id) > BigInt(current.id) } catch { return false }
  }
  const currentEpoch = Number(current.collaboration.epoch), nextEpoch = Number(next.collaboration.epoch)
  if (next.revision < current.revision || nextEpoch < currentEpoch) return false
  return nextEpoch !== currentEpoch || Number(next.collaboration.serverTime) >= Number(current.collaboration.serverTime)
}
