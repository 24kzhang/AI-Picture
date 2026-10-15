import { readdir, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { hypitRoot, hypit, writeWorkspace, sourceFor, readState, type SourceBundle } from './hypit.js'
import { sourceFiles, normalizeSource, runEntry } from './source-bundle.js'
import { applyOperations, emptyState, duration, type Asset } from './timeline.js'
import { invariant } from './errors.js'
import { assertBuildReferences } from './projects.js'

export async function catalog() {
  const packages=await readdir(resolve(hypitRoot,'packages'),{withFileTypes:true})
  return {version:'0.2.16',packages:await Promise.all(packages.filter(p=>p.isDirectory()).map(async p=>{
    let documentation='';try{documentation=await readFile(resolve(hypitRoot,'packages',p.name,'README.md'),'utf8')}catch{}
    return {name:p.name,module:`@hypit/${p.name}@1`,summary:documentation.split('\n').filter(l=>l.trim()&&!l.startsWith('#')).slice(0,3).join(' ').slice(0,320),documentation}
  }))}
}
const vocabularyCache=new Map<string,Promise<any>>()
export async function vocabulary(name:string,workspace:string) {
  name=name.replace(/^@hypit\//,'').replace(/@1$/,'')
  invariant(/^[a-z0-9-]+$/.test(name)&&(await catalog()).packages.some(p=>p.name===name),'组件不存在')
  let pending=vocabularyCache.get(name)
  if(!pending){pending=hypit(['vocabulary',`@hypit/${name}`],workspace).then(JSON.parse);vocabularyCache.set(name,pending);pending.catch(()=>vocabularyCache.delete(name))}
  const {projectRoot,...result}=await pending
  return {...result,surfaces:(result.surfaces||[]).map(({readme,...surface}:any)=>surface)}
}
export async function analyzeSource(id:string,input:SourceBundle,assets:Asset[],operation:'check'|'plan'|'doctor') {
  const source=normalizeSource(input,assets);await assertBuildReferences(id,source)
  const workspace=await writeWorkspace(id,`inspect-${crypto.randomUUID()}`,source,assets)
  const args=operation==='doctor'?['doctor',resolve(workspace,'hypit.runtime.json')]:[operation,resolve(workspace,runEntry(source))]
  return JSON.parse(await hypit(args,workspace))
}
export const templates=[
  {id:'current',name:'保留当前编排',description:'将当前项目转换为完整 Hypit 源工程，素材、布局与时序原样保留。'},
  {id:'travel',name:'旅行故事',description:'两段真实动景、中文标题和本地屏幕光效，可继续编排语义组件。'},
  {id:'split-screen',name:'双画面比较',description:'真实素材并排展示，独立声音控制，完整源文件和样式。'},
  {id:'portrait',name:'竖屏讲解',description:'9:16 真实素材和中文标题，适合继续添加配音与字幕。'},
  {id:'ranking',name:'旅行地点排名',description:'使用原生 Ranking 排名组件叠加真实素材，保留可编辑的行、样式和时间区间。'},
  {id:'cards',name:'素材深度卡片',description:'使用原生 Deck 深度卡片，在不同激活时刻呈现两段真实素材。'},
  {id:'comment',name:'社交评论贴纸',description:'原生 Comment Sticker 的文字、作者、外观与局部动画，叠加真实动景。'},
  {id:'semantic',name:'语义配音与逐字字幕',description:'使用真实配音素材和对应台词，原生 Script / WhisperX / Caption Fine 字级对齐及高亮。'},
] as const
export function templateSource(name:string,assets:Asset[],current:SourceBundle,args?:{text?:string;voiceId?:string}):SourceBundle {
  invariant(templates.some(t=>t.id===name),'模板不存在')
  if(name==='current')return normalizeSource({...current,mode:'hypit',state:readState(current)},assets)
  if(name==='semantic')return semanticSource(assets,args?.text||'',args?.voiceId||'')
  const videos=assets.filter(a=>a.kind==='video');invariant(videos.length,'模板需要至少一段真实视频素材，请先上传')
  let state=emptyState(),operations:any[]=[]
  if(name==='portrait')operations.push({type:'set_layout',width:720,height:1280})
  const first=videos[0]!,second=videos[1]||first,frames=Math.min(first.frames,96)
  operations.push({type:'insert_clip',assetId:first.id,trackId:'visual',at:0,length:frames})
  if(name==='travel'||name==='cards')operations.push({type:'insert_clip',assetId:second.id,trackId:'visual',at:frames,length:Math.min(second.frames,96)})
  if(name==='split-screen')operations.push({type:'insert_clip',assetId:second.id,trackId:'visual',at:0,length:Math.min(frames,second.frames)})
  operations.push({type:'insert_clip',trackId:'titles',at:0,length:frames,text:name==='split-screen'?'同一旅程 · 两种视角':name==='portrait'?'城市与自然\n真实旅程':'从森林走向城市'})
  // SourceFor owns exact trims and linked audio; richer native structure is appended afterwards.
  state=applyOperations(state,operations,assets)
  state=applyOperations(state,[{type:'edit_subtitles',clipId:state.clips.find(c=>c.kind==='text')!.id,fontSize:48,y:name==='portrait'?-420:-220}],assets)
  if(name==='split-screen'){
    const clips=state.clips.filter(c=>c.kind==='video')
    state=applyOperations(state,clips.map((c,i)=>({type:'set_layout',clipId:c.id,scale:.5,x:i?-state.width/-4:-state.width/4,fit:'cover'})),assets)
    const linked=state.clips.filter(c=>c.kind==='audio');if(linked[1])state=applyOperations(state,[{type:'set_audio',clipId:linked[1].id,muted:true}],assets)
  }
  const source=sourceFor(state,assets)
  const add=(imports:string,body:string,track:string,styles:string)=>{
    source['main.svml']=source['main.svml'].replace('<svml>','<svml>\n'+imports).replace('<film:Film id="main"',body+'\n<film:Film id="main"').replace('</film:Film>',`<film:Track source={${track}}/></film:Film>`)
    source['main.svs']=source['main.svs'].replace('</sheet>',styles+'\n</sheet>')
  }
  if(name==='ranking')add('<import as="ranking" from="@hypit/ranking@1"/>',
    '<space:AnchoredFrame id="board-frame" within={canvas} x="640px" y="490px" width="660px" height="280px" anchor="center"/>\n<ranking:ColumnStyle id="board-style" recipe={style.ranking.board} font={font}/><ranking:Column id="board" timeline={program.timeline} canvas={canvas} frame={board-frame} during="program" style={board-style}><ranking:ColumnItem id="forest" rank="1" label="森林与溪流" preset="true"/><ranking:ColumnItem id="city" rank="2" label="街道与咖啡" preset="true"/></ranking:Column>',
    'board.visual','ranking.board { board-stack: 6000; item-stack: 6100; stage-stack: 6200; font-size: 30; board-background: #172c23; }')
  if(name==='cards')add('<import as="deck" from="@hypit/deck-track@1"/>',
    `<space:AnchoredFrame id="deck-frame" within={canvas} x="640px" y="420px" width="660px" height="400px" anchor="center"/><deck:DepthStack id="story-cards" timeline={program.timeline} canvas={canvas} frame={deck-frame} until="${duration(state)}f" appearance={style.deck.story}><deck:Card id="first-card" source={n-${first.id}.media} at="0f"/><deck:Card id="second-card" source={n-${second.id}.media} at="${frames}f"/></deck:DepthStack>`,
    'story-cards.track','deck.story { current-stacking: 6000; current-scale: 1; visible-previous: 1; visible-next: 1; reflow-frames: 8; }')
  if(name==='comment')add('<import as="comment" from="@hypit/comment-sticker@1"/><import as="fonts" from="@hypit/fonts-open@1"/>',
    '<fonts:Stack id="comment-fonts" family="noto-sans-sc" weight="700" style="normal"/><space:AnchoredFrame id="comment-frame" within={canvas} x="640px" y="510px" width="650px" height="200px" anchor="center"/><comment:Style id="comment-style" recipe={style.comment.social} font={comment-fonts}/><comment:Track id="social-comments" canvas={canvas} timeline={program.timeline}><comment:Sticker id="viewer-note" frame={comment-frame} style={comment-style} author="旅行记录" meta="真实素材场景" during="program">沿着溪流，记住旅途中的安静。</comment:Sticker></comment:Track>',
    'social-comments.track','comment.social { stack-order: 6000; background: #172c23; body-size: 30; body-color: #ffffff; enter: slide-pop; enter-frames: 8; }')
  if(name==='travel')source['main.svml']=source['main.svml'].replace('<svml>','<svml>\n<import as="screen" from="@hypit/screen-overlay@1"/>').replace('<film:Film id="main"',`<screen:Track id="effects" timeline={program.timeline} canvas={canvas}><screen:Flash at="${frames}f" for="12f" z="9000" color="#ffffff" intensity="0.3" attack="2" hold="2" decay="8"/></screen:Track>\n<film:Film id="main"`).replace('</film:Film>','<film:Track source={effects.track}/></film:Film>')
  return normalizeSource({...source,mode:'hypit',state},assets)
}
export function semanticSource(assets:Asset[],text:string,voiceId:string):SourceBundle {
  invariant(text.trim().length>0&&text.length<=4000,'语义工程需要实际配音对应的台词')
  const voice=assets.find(a=>a.id===voiceId),video=assets.find(a=>a.kind==='video')
  invariant(voice?.kind==='audio'&&voice.hasAudio&&video,'请选择本项目的真实配音音频素材，并上传一段真实视频')
  const pictures:any[]=[];for(let at=0;at<voice.frames;at+=video.frames)pictures.push({type:'insert_clip',assetId:video.id,trackId:'visual',at,length:Math.min(video.frames,voice.frames-at)})
  let state=applyOperations(emptyState(),[...pictures,{type:'insert_clip',assetId:voice.id,trackId:'voice',at:0,length:voice.frames},{type:'insert_clip',trackId:'titles',at:0,length:voice.frames,text:'旅途中的声音'}],assets)
  state=applyOperations(state,state.clips.filter(c=>c.trackId==='original').map(c=>({type:'set_audio',clipId:c.id,muted:true})),assets)
  const title=state.clips.find(c=>c.kind==='text')!;state=applyOperations(state,[{type:'edit_subtitles',clipId:title.id,y:-240,fontSize:48}],assets)
  const source=sourceFor(state,assets)
  source['main.svml']=source['main.svml'].replace('<svml>','<svml>\n<import as="script" from="@hypit/script@1"/><import as="whisperx" from="@hypit/whisperx@1"/><import as="caption" from="@hypit/caption-fine@1"/>')
    .replace('<film:Film id="main"',`<caption:Style id="word-style" recipe={style.caption.words} font={font}/><caption:Track id="word-captions" document={story.caption} timeline={program.timeline}><caption:Use style={word-style}/></caption:Track>\n<film:Film id="main"`)
    .replace('</film:Film>','<film:Track source={word-captions.track}/></film:Film>')
  source['main.svs']=source['main.svs'].replace('</sheet>','caption.words { stack-order: 7000; x: 0.5; y: 0.85; width: 0.9; height: 0.2; anchor-x: center; anchor-y: bottom; align: center; block-align: center; inline-size: fixed; wrap: word; size: 42; line-height: 1.2; fill: #ffffff; background: #00000000; padding: "0"; radius: 0; karaoke: current; active-fill: #ffd54a; lead-frames: 0; tail-frames: 0; handoff: cut; }\n</sheet>')
  // Structured decoders resolve already published typed ports. Prepare media and
  // its SemanticTake before the Timeline; a forward Take is not a valid binding.
  const media=[...source['main.svml'].matchAll(/<(?:media:(?:Video|Audio)|pipeline:Normalize)\b[^>]+\/>/g)].map(m=>m[0])
  for(const declaration of media)source['main.svml']=source['main.svml'].replace(declaration,'')
  source['main.svml']=source['main.svml'].replace(/<time:Timeline id="program"[^>]+\/>/,media.join('\n')+`\n<script:script id="story"><voice>${captionCues(text)}</voice></script:script>\n<whisperx:SemanticTake id="voice-semantic" narrative={story} segment={story.segment.voice} media={n-${voice.id}.media} language="zh"/>\n<time:Timeline id="program" clock={clock} end="${voice.frames}f"><time:Take source={voice-semantic.take} at="0f"/></time:Timeline>`)
  return normalizeSource({...source,mode:'hypit',state},assets)
}

// Cue boundaries only change presentation; WhisperX still aligns the complete
// original speech. Preserve whole words and escape plain text as Script prose.
export function captionCues(text:string):string {
  const chunks:string[]=[];let current='',length=0
  for(const {segment} of new Intl.Segmenter('zh',{granularity:'word'}).segment(text)){
    const count=Array.from(segment).length
    if(length&&length+count>20&&/[\p{L}\p{N}]/u.test(segment)){chunks.push(current);current='';length=0}
    current+=segment;length+=count
    if(/[。！？!?；;]$/u.test(segment)&&length>=8){chunks.push(current);current='';length=0}
  }
  if(current)chunks.push(current)
  return chunks.map(chunk=>chunk.replace(/[\\@<|{}]/g,'\\$&')).join('||')
}
