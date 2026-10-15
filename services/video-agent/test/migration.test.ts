import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
test('all video tables explicitly require the transactional InnoDB engine',async()=>{
  const sql=await readFile(new URL('../migrations/001-video.sql',import.meta.url),'utf8'),tables=sql.split(';').filter(s=>s.includes('CREATE TABLE'))
  assert.equal(tables.length,10)
  for(const table of tables)assert.match(table,/\) ENGINE=InnoDB CHARACTER SET utf8mb4\s*$/)
  const clock=await readFile(new URL('../migrations/002-utc-clock.sql',import.meta.url),'utf8')
  assert.match(clock,/\) ENGINE=InnoDB CHARACTER SET utf8mb4\s*;/)
})
