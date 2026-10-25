/** Stop video service first. Backup all video tables before the additive engine migration. */
import assert from 'node:assert/strict'
import {writeFile} from 'node:fs/promises'
import {createHash} from 'node:crypto'
import {resolve} from 'node:path'
import mysql from 'mysql2'
import {db,rows} from '../src/db.js'
import {root} from '../src/config.js'
import {migrate,videoTables} from '../src/migrate.js'
const dir=resolve(root,'runtime-data/video-acceptance'),backup=resolve(dir,`video-db-before-innodb-${Date.now()}.sql`),sql=['SET NAMES utf8mb4;'],before:any={}
try{
  for(const table of videoTables){
    const columns=await rows('SELECT COLUMN_NAME AS name,DATA_TYPE AS type FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION',[table])
    const projection=columns.map(c=>['datetime','timestamp','date'].includes(c.type)?`CAST(\`${c.name}\` AS CHAR) AS \`${c.name}\``:`\`${c.name}\``).join(','),data=await rows(`SELECT ${projection} FROM \`${table}\``),create=(await rows(`SHOW CREATE TABLE \`${table}\``))[0]!
    sql.push(String(create['Create Table'])+';')
    for(const row of data)sql.push(`INSERT INTO \`${table}\` (${columns.map(c=>'`'+c.name+'`').join(',')}) VALUES (${columns.map(c=>mysql.escape(row[c.name]&&typeof row[c.name]==='object'?JSON.stringify(row[c.name]):row[c.name])).join(',')});`)
    before[table]={count:data.length,hash:createHash('sha256').update(JSON.stringify(data.map(r=>JSON.stringify(r)).sort())).digest('hex'),projection}
  }
  await writeFile(backup,sql.join('\n'),'utf8')
  await migrate();await migrate()
  const engines=await rows('SELECT TABLE_NAME AS name,ENGINE AS engine FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (?)',[videoTables])
  assert.equal(engines.length,10);assert.ok(engines.every(r=>r.engine==='InnoDB'))
  for(const table of videoTables){const data=await rows(`SELECT ${before[table].projection} FROM \`${table}\``);assert.equal(data.length,before[table].count);assert.equal(createHash('sha256').update(JSON.stringify(data.map(r=>JSON.stringify(r)).sort())).digest('hex'),before[table].hash);delete before[table].projection}
  await writeFile(resolve(dir,'continuous-storage-report.json'),JSON.stringify({completedAt:new Date().toISOString(),backup,engines,tables:before,checks:['all 10 video tables use InnoDB','second migration is idempotent','every existing row retained byte-equivalent values']},null,2),'utf8');console.log('视频数据库迁移通过，10 张表原数据完整；备份:',backup)
}finally{await db.end()}
