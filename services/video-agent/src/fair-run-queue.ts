export type QueueJob = {
  id: string
  userId: string
  work: () => Promise<void>
}

export type QueueResult = 'accepted' | 'duplicate' | 'rejected'

type QueueOptions = {
  maxConcurrent: number
  maxConcurrentPerUser: number
  maxQueuedPerUser: number
}

/** In-process fair scheduler. Database state remains authoritative for recovery. */
export class FairRunQueue {
  private readonly queues = new Map<string, QueueJob[]>()
  private readonly active = new Map<string, number>()
  private readonly scheduled = new Set<string>()
  private readonly order: string[] = []
  private cursor = 0
  private running = 0

  constructor(private readonly options: QueueOptions) {
    if (!Number.isInteger(options.maxConcurrent) || options.maxConcurrent < 1) throw new Error('maxConcurrent must be positive')
    if (!Number.isInteger(options.maxConcurrentPerUser) || options.maxConcurrentPerUser < 1) throw new Error('maxConcurrentPerUser must be positive')
    if (!Number.isInteger(options.maxQueuedPerUser) || options.maxQueuedPerUser < 0) throw new Error('maxQueuedPerUser must be non-negative')
  }

  enqueue(job: QueueJob): QueueResult {
    if (this.scheduled.has(job.id)) return 'duplicate'
    const queue = this.queues.get(job.userId) || []
    if (queue.length >= this.options.maxQueuedPerUser) return 'rejected'
    this.scheduled.add(job.id)
    if (!this.queues.has(job.userId)) {
      this.queues.set(job.userId, queue)
      this.order.push(job.userId)
    }
    queue.push(job)
    this.pump()
    return 'accepted'
  }

  get runningCount() { return this.running }
  queuedCount(userId: string) { return this.queues.get(userId)?.length || 0 }
  activeCount(userId: string) { return this.active.get(userId) || 0 }

  private next(): QueueJob | undefined {
    if (!this.order.length) return undefined
    for (let checked = 0; checked < this.order.length; checked++) {
      if (this.cursor >= this.order.length) this.cursor = 0
      const userId = this.order[this.cursor++]!
      const queue = this.queues.get(userId)
      if (!queue?.length || (this.active.get(userId) || 0) >= this.options.maxConcurrentPerUser) continue
      return queue.shift()
    }
    return undefined
  }

  private pump() {
    while (this.running < this.options.maxConcurrent) {
      const job = this.next()
      if (!job) return
      this.running++
      this.active.set(job.userId, (this.active.get(job.userId) || 0) + 1)
      void Promise.resolve()
        .then(job.work)
        .catch(() => undefined)
        .finally(() => {
          this.running--
          this.active.set(job.userId, Math.max(0, (this.active.get(job.userId) || 1) - 1))
          this.scheduled.delete(job.id)
          const queue = this.queues.get(job.userId)
          if (!queue?.length && !this.active.get(job.userId)) {
            this.queues.delete(job.userId)
            this.active.delete(job.userId)
            const index = this.order.indexOf(job.userId)
            if (index >= 0) {
              this.order.splice(index, 1)
              if (index < this.cursor) this.cursor--
              if (this.cursor < 0) this.cursor = 0
            }
          }
          this.pump()
        })
    }
  }
}
