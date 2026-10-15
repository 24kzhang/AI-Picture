import mysql, { type PoolConnection, type RowDataPacket } from 'mysql2/promise'
import { config } from './config.js'
export const db = mysql.createPool(config.database)
// DATETIME defaults and explicit UTC_TIMESTAMP writes must use the same clock.
// mysql2's `timezone: Z` only controls encoding/decoding, not MySQL's session.
db.on('connection',connection=>{
  connection.query("SET SESSION time_zone = '+00:00'",(error:Error|null)=>{if(error)connection.destroy()})
})
export async function rows<T = Record<string, any>>(sql: string, values: any[] = [], connection: PoolConnection | typeof db = db): Promise<T[]> {
  const [result] = await connection.query<RowDataPacket[]>(sql, values)
  return result as T[]
}
export async function transaction<T>(fn: (connection: PoolConnection) => Promise<T>): Promise<T> {
  const connection = await db.getConnection()
  try { await connection.beginTransaction(); const result = await fn(connection); await connection.commit(); return result }
  catch (error) { await connection.rollback(); throw error }
  finally { connection.release() }
}
export const json = <T = any>(value: T | string): T => typeof value === 'string' ? JSON.parse(value) : value
