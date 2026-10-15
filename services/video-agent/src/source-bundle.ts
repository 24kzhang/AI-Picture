import { posix } from 'node:path'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { ApiError, invariant } from './errors.js'
import { hypitRoot, type SourceBundle } from './hypit.js'
import type { Asset } from './timeline.js'
import { z } from 'zod'

export const sourceFiles = (source: SourceBundle): Record<string,string> => ({
  'main.svml':source['main.svml'],'main.svs':source['main.svs'],'build.svrun':source['build.svrun'],...source.files,
})
export const runEntry = (source:SourceBundle) => source.entry || 'build.svrun'
export function sourcePath(name:string) {
  invariant(name.length<=160 && /^[\p{L}\p{N}_.\/-]+\.(?:svml|svs|svrun|json)$/u.test(name) && !name.startsWith('/') && name.split('/').every(p=>p!=='.'&&p!=='..'&&p!==''), '源文件必须使用工程内的相对路径')
  invariant(!name.split('/').some(p=>/^(?:node_modules|assets|package\.json|hypit\.(?:runtime|results)\.json|FEEDBACK\.json)$/i.test(p)), '不能覆盖运行配置、素材或评论文件')
  return name
}
export function normalizeSource(input:SourceBundle,assets:Asset[]):SourceBundle {
  invariant(input && typeof input==='object','缺少工程源文件')
  const files=sourceFiles(input),names=Object.keys(files)
  invariant(names.length<=32,'工程最多包含 32 个源文件')
  invariant(names.every(name=>typeof files[name]==='string'),'源文件内容必须是文本')
  invariant(names.reduce((n,name)=>n+Buffer.byteLength(files[name]!,'utf8'),0)<=1024**2,'工程源文件总量超过 1 MiB')
  names.forEach(sourcePath)
  const entry=sourcePath(runEntry(input));invariant(entry.endsWith('.svrun')&&files[entry]!==undefined,'运行入口必须是已有 SVRun 文件')
  invariant(input.mode===undefined||input.mode==='timeline'||input.mode==='hypit','无效工程模式')
  if(input.feedback){
    const comments=z.array(z.object({id:z.string().min(1).max(100),run:z.string().min(1).max(160),at:z.number().finite().nonnegative(),text:z.string().min(1).max(10000),resolved:z.boolean().optional()}).passthrough()).max(1000).parse(input.feedback.comments)
    invariant(new Set(comments.map(c=>c.id)).size===comments.length,'评论标识重复')
    comments.forEach(c=>invariant(c.run in files&&c.run.endsWith('.svrun'),'评论必须属于本工程的运行入口'))
  }
  const ids=new Set<string>()
  for(const [name,text] of Object.entries(files)) {
    invariant(!text.includes('\0'),'源文件包含无效字符')
    if(name.endsWith('.json')){try{JSON.parse(text)}catch{throw new ApiError(422,'INVALID_JSON',`${name} 不是有效 JSON`)};continue}
    invariant(!/<!DOCTYPE|<!ENTITY|<iframe\b/iu.test(text),'不允许可执行脚本或外部实体')
    // All dependencies are audited packaged Hypit code or immutable project data.
    const bare=text.replace(/<!--[\s\S]*?-->/g,'')
    for(const header of bare.matchAll(/<\?svml\s+using=["']([^"']+)["']/g))invariant(['@hypit/markup@1','@hypit/svs@1','@hypit/run-markup@1'].includes(header[1]!),'只允许内置声明式源语言')
    for(const match of bare.matchAll(/\b(?:src|source|from)\s*=\s*(["'])(.*?)\1/gs)) {
      const value=match[2]!
      if(value.startsWith('@hypit/')) {
        const pkg=/^@hypit\/([a-z0-9-]+)@1$/.exec(value)?.[1]
        invariant(pkg&&existsSync(resolve(hypitRoot,'packages',pkg)),`不支持的组件包 ${value}`)
        continue
      }
      invariant(value.startsWith('./')||value.startsWith('../'),`${name} 只能引用项目内素材或源文件：${value}`)
      const target=posix.normalize(posix.join(posix.dirname(name),value))
      invariant(!target.startsWith('../')&&!target.startsWith('/')&&!value.includes('\\'), '引用不能越出工程目录')
      if(target==='assets/black.png'||target==='assets/font.ttf')continue
      const asset=/^assets\/([0-9a-f-]{36})\.(png|mp4|wav)$/i.exec(target)
      if(asset){const found=assets.find(a=>a.id===asset[1]);invariant(found,'源文件引用了不属于本项目的素材');invariant(asset[2]===(found.kind==='image'?'png':found.kind==='video'?'mp4':'wav'),'素材扩展名与类型不一致');ids.add(found.id)}
      else invariant(files[target]!==undefined,`缺少引用文件 ${target}`)
    }
    invariant(!/\b(?:src|from)\s*=\s*\{|<import\b[^>]*\bsource\s*=\s*\{/u.test(bare),'资源路径必须为受控的静态字符串')
    invariant(!/(?:https?:|file:|data:|javascript:)\/\/|\burl\s*\(/iu.test(bare),'不允许引用外部资源')
  }
  return {...input,...Object.fromEntries(['main.svml','main.svs','build.svrun'].map(n=>[n,files[n]])),files,entry,mode:input.mode||'timeline',assetIds:[...ids]}
}
export function outputTargets(source:SourceBundle) {
  return [...sourceFiles(source)[runEntry(source)]!.matchAll(/<target\b[^>]*\boutput=["']([\w.:-]+)["'][^>]*\/?\s*>/g)].map(m=>m[1]!)
}
