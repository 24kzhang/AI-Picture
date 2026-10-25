import assert from 'node:assert/strict'
import {once} from 'node:events'
import {readFile,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {root} from '../src/config.js'
import {db} from '../src/db.js'
import {project} from '../src/projects.js'
import {openStudio,closeStudios} from '../src/studio.js'
const dir=resolve(root,'runtime-data/video-acceptance'),cookie=JSON.parse(await readFile(resolve(dir,'accounts.json'),'utf8'))[0].cookie,checks:string[]=[]
async function create(name:string){const response=await fetch('http://127.0.0.1:8091/api/video-editor/projects',{method:'POST',headers:{Cookie:cookie,'Content-Type':'application/json'},body:JSON.stringify({name})});assert.ok(response.ok);return project((await response.json() as any).id)}
try{
  const a=await create('Studio 恢复 A'),b=await create('Studio 恢复 B'),c=await create('Studio 恢复 C')
  const first=await openStudio(a,[]),second=await openStudio(b,[])
  assert.notEqual(first.port,second.port);checks.push('two independent Studio projects start')
  const exited=once(first.process,'exit');first.process.kill();await exited
  const restarted=await openStudio(a,[]),third=await openStudio(c,[])
  assert.notEqual(restarted.port,third.port);assert.notEqual(second.port,third.port)
  for(const session of [restarted,second,third]){const response=await fetch(`http://127.0.0.1:${session.port}${session.base}`);assert.ok(response.ok);assert.ok((await response.text()).includes('<html'))}
  checks.push('restart A, then open C: all live sessions have distinct ports and respond')
  await writeFile(resolve(dir,'continuous-studio-report.json'),JSON.stringify({completedAt:new Date().toISOString(),checks,ports:[restarted.port,second.port,third.port]},null,2),'utf8');console.log('Studio 生命周期回归通过',checks.length)
}finally{closeStudios();await db.end()}
