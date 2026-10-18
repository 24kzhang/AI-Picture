import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import {resolve} from 'node:path'
import mysql from 'mysql2/promise'
import {config,dataRoot} from './config.js'
import { db,rows,json } from './db.js'
export const videoTables=['video_project','video_revision','video_quota','video_object','video_asset','video_record','video_run','video_step','video_event','video_idempotency','video_migration','video_annotation_member'] as const
export async function migrate() {
  for(const name of ['001-video.sql','002-utc-clock.sql','003-annotations.sql']){
    const sql = await readFile(fileURLToPath(new URL('../migrations/'+name, import.meta.url)), 'utf8')
    for (const statement of sql.split(';').map(s => s.trim()).filter(Boolean)) await db.query(statement)
  }
  const runColumns:Record<string,string>={worker_id:'VARCHAR(64) NULL',lease_epoch:'BIGINT NOT NULL DEFAULT 0',lease_until:'DATETIME(3) NULL'}
  const installedRuns=new Set((await rows("SELECT COLUMN_NAME AS name FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='video_run'")).map(c=>c.name))
  for(const [name,type] of Object.entries(runColumns))if(!installedRuns.has(name))await db.query(`ALTER TABLE video_run ADD COLUMN \`${name}\` ${type}`)
  const reviewColumns:Record<string,string>={review_source:"VARCHAR(20) NOT NULL DEFAULT 'legacy'",reviewer_name:'VARCHAR(100) NULL',reviewer_id:'BIGINT NULL',review_message:'VARCHAR(1000) NULL',review_time:'DATETIME(3) NULL',review_version:'INT NOT NULL DEFAULT 0'}
  const installed=new Set((await rows('SELECT COLUMN_NAME AS name FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=\'video_record\'')).map(c=>c.name))
  for(const [name,type] of Object.entries(reviewColumns))if(!installed.has(name))await db.query(`ALTER TABLE video_record ADD COLUMN \`${name}\` ${type}`)
  // Existing installations may have inherited a nontransactional default engine.
  // These tables rely on rollback and SELECT FOR UPDATE for revision/lease/quota gates.
  const engines=await rows('SELECT TABLE_NAME AS name,ENGINE AS engine FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (?)',[videoTables])
  for(const table of engines)if(table.engine!=='InnoDB')await db.query(`ALTER TABLE \`${table.name}\` ENGINE=InnoDB`)
  await normalizeLegacyClock()
}
async function normalizeLegacyClock(){
  const connection=await db.getConnection(),lock='gallery-video-utc-'+config.database.database
  let locked=false
  try{
    locked=Number((await rows('SELECT GET_LOCK(?,30) AS acquired',[lock.slice(0,64)],connection))[0]?.acquired)===1
    if(!locked)throw new Error('视频时间迁移锁等待超时')
    const existing=await rows("SELECT details FROM video_migration WHERE id='utc-clock@1'",[],connection)
    if(existing.length)return json(existing[0]!.details)
    // This separate connection intentionally observes the original server session
    // clock before the application pool's UTC initialization.
    const legacy=await mysql.createConnection(config.database)
    let offset:number
    try{offset=Number((await legacy.query<any[]>('SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW()) AS seconds'))[0][0].seconds)}finally{await legacy.end()}
    if(!Number.isFinite(offset)||Math.abs(offset)>14*3600)throw new Error('数据库原时间偏移无法确认')
    const tables=['video_project','video_revision','video_asset','video_record','video_run','video_event'],backup:any={offsetSeconds:offset,createdAt:new Date().toISOString(),tables:{}}
    // Back up only timestamp identities, never credentials, source or media data.
    for(const name of tables){const keys=name==='video_revision'?'project_id,revision':'id',updated=['video_project','video_run'].includes(name)?',CAST(updated_at AS CHAR) AS updated_at':'';backup.tables[name]=await rows(`SELECT ${keys},CAST(created_at AS CHAR) AS created_at${updated} FROM \`${name}\``,[],connection)}
    const path=resolve(dataRoot,`legacy-clock-backup-${Date.now()}.json`)
    await writeFile(path,JSON.stringify(backup,null,2),{encoding:'utf8',flag:'wx'})
    await connection.beginTransaction()
    try{
      if(offset)for(const name of tables){const updated=['video_project','video_run'].includes(name)?'updated_at=IF(updated_at=created_at,TIMESTAMPADD(SECOND,?,updated_at),updated_at),':'';await connection.query(`UPDATE \`${name}\` SET ${updated}created_at=TIMESTAMPADD(SECOND,?,created_at)`,updated?[-offset,-offset]:[-offset])}
      await connection.query("INSERT INTO video_migration(id,details) VALUES ('utc-clock@1',?)",[JSON.stringify({offsetSeconds:offset,backup:path,completedAt:new Date().toISOString()})])
      await connection.commit()
    }catch(error){await connection.rollback();throw error}
  }finally{if(locked)await connection.query('SELECT RELEASE_LOCK(?)',[lock.slice(0,64)]);connection.release()}
}
if (process.argv[1]?.endsWith('migrate.ts')) { await migrate(); console.log('视频表迁移完成'); await db.end() }
