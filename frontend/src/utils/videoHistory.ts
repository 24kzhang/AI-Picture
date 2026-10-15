/** Only move the history cursor after the server has accepted the restore. */
export async function restoreHistory(
  source: number[], destination: number[], currentRevision: number,
  restore: (revision: number) => Promise<boolean>,
): Promise<void> {
  const target = source[source.length - 1]
  if (target === undefined || !await restore(target)) return
  source.pop()
  destination.push(currentRevision)
}
