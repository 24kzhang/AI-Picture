const labels:Record<string,string>={insert_clip:'插入片段',trim_clip:'裁剪',split_clip:'分割',move_clip:'移动',duplicate_clip:'复制',delete_clip:'删除',unlink_clip:'解除关联',set_layout:'画面调整',set_audio:'音频调整',edit_subtitles:'字幕编辑',add_track:'新增轨道',reorder_track:'轨道排序',transcribe:'中文字幕转写'}
export function executionReceipt(value:{modelAnswer:string;applied:boolean;pending:boolean;generatedCount:number;revision:number;tools:string[];exportFrames?:number;exportFps?:number;toolErrors:number}):string {
  if(value.pending)return '生成素材已保留，项目版本或编辑权已改变，请手动插入。'
  const parts:string[]=[]
  if(value.applied){const counts=new Map<string,number>();for(const name of value.tools)if(labels[name])counts.set(name,(counts.get(name)||0)+1);parts.push(`已保存项目 v${value.revision}。`);if(counts.size)parts.push(`已执行：${[...counts].map(([name,count])=>`${labels[name]} ${count} 次`).join('、')}。`)}
  if(value.generatedCount)parts.push(`已生成并保存 ${value.generatedCount} 个素材。`)
  if(value.exportFrames!==undefined)parts.push(`已导出 ${value.exportFrames} 帧 MP4（${(value.exportFrames/(value.exportFps||24)).toFixed(2)} 秒）。`)
  if(value.toolErrors)parts.push(`执行中有 ${value.toolErrors} 次工具调用失败，详情保留在任务记录中；失败操作未应用。`)
  if(!value.applied&&!value.generatedCount&&value.exportFrames===undefined)parts.unshift('本次未修改项目。',value.modelAnswer)
  return parts.join('\n')
}
