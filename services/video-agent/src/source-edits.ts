import {z} from 'zod'
import {invariant} from './errors.js'

const metadata=/<!--\s*gallery-state:[\s\S]*?-->/g
export function plannerSourceFiles(files:Record<string,string>) {
  return Object.fromEntries(Object.entries(files).map(([name,text])=>[name,text.replace(metadata,'')]))
}
export function preserveSourceMetadata(original:Record<string,string>,edits:Record<string,string>) {
  return Object.fromEntries(Object.entries(edits).map(([name,text])=>{
    const marker=original[name]?.match(metadata)?.[0]
    if(marker&&text.includes(marker)&&text.match(metadata)?.length===1)return [name,text]
    const clean=text.replace(metadata,'')
    if(!marker)return [name,clean]
    const root=/<svml(?:\s[^>]*)?>/.exec(clean)
    return [name,root?clean.replace(root[0],root[0]+'\n'+marker):marker+'\n'+clean]
  }))
}
const replacementSchema=z.array(z.object({path:z.string().min(1).max(160),find:z.string().min(1).max(20000),replace:z.string().max(20000)}).strict()).min(1).max(16)

/** 只替换一个明确位置，任何缺失或歧义均拒绝整个候选，不改原工程。 */
export function replaceSourceText(original:Record<string,string>,raw:unknown) {
  const edits=replacementSchema.parse(raw),changed:Record<string,string>={}
  for(const edit of edits) {
    const text=changed[edit.path]??original[edit.path]
    invariant(typeof text==='string',`源文件不存在：${edit.path}`)
    const index=text.indexOf(edit.find)
    invariant(index>=0,`在 ${edit.path} 中找不到需要替换的原文`)
    invariant(text.indexOf(edit.find,index+edit.find.length)<0,`在 ${edit.path} 中原文不唯一，请包含更多上下文`)
    invariant(!edit.find.includes('gallery-state:')&&!edit.replace.includes('gallery-state:'),'不能编辑系统生成的画布元数据')
    changed[edit.path]=text.slice(0,index)+edit.replace+text.slice(index+edit.find.length)
  }
  return changed
}
