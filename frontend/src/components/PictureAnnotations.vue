<template>
  <section class="annotation-room">
    <div class="room-toolbar">
      <strong>多人画笔 / 文字 / 贴图 · {{ room?.members.length || 0 }}/5</strong>
      <a-button v-if="!held" type="primary" :disabled="!editable || busy" @click="join">加入多人画布</a-button>
      <a-button v-else :disabled="busy || !!pending" @click="leave">退出多人画布</a-button>
      <span>{{ held ? '正在共同创作，操作自动保存' : '观看模式' }}</span>
    </div>
    <a-alert type="info" show-icon message="画笔、文字和贴图可同时编辑。裁剪、滤镜、AI 与正式提交需全员退出后获取独占编辑权。" />
    <a-alert v-if="error" type="warning" show-icon :message="error" />
    <div v-if="pending && !busy" class="room-toolbar">
      <span>本地操作待处理</span><a-button @click="retry">重试原请求</a-button>
      <a-button :disabled="!held" @click="adoptLocal">核对后采用本地操作</a-button><a-button @click="discard">放弃本地操作并同步</a-button>
    </div>
    <div class="room-toolbar">
      <a-button :type="tool==='brush'?'primary':'default'" :disabled="!canEdit" @click="tool='brush'">画笔</a-button>
      <a-button :type="tool==='select'?'primary':'default'" @click="tool='select'">选择 / 拖动贴图</a-button>
      <input v-model="color" type="color" aria-label="多人画笔颜色" :disabled="!canEdit" />
      <input v-model.number="width" type="range" min="2" max="40" aria-label="多人画笔宽度" :disabled="!canEdit" />
      <a-input v-model:value="text" placeholder="多人画布文字" style="width:180px" :disabled="!canEdit" @press-enter="addText" />
      <a-button :disabled="!canEdit || !text.trim()" @click="addText">添加文字</a-button>
      <a-button v-for="sticker in ['⭐','❤️','🌿','✨']" :key="sticker" :disabled="!canEdit" @click="addRaster(sticker,true)">{{ sticker }}</a-button>
      <a-button :disabled="!canEdit || !selected" danger @click="removeSelected">删除选中对象</a-button>
    </div>
    <canvas ref="canvas" aria-label="多人协作画布" @pointerdown="start" @pointermove="move" @pointerup="end" @pointercancel="cancel" />
    <div class="object-list"><a-button v-for="object in visibleObjects" :key="object.id" size="small" :type="selected===object.id?'primary':'default'" @click="selected=object.id">{{object.kind==='stroke'?'笔迹':'文字 / 贴图'}} · {{object.id.slice(0,6)}}</a-button></div>
  </section>
</template>
<script setup lang="ts">
import {computed,ref,onMounted,onBeforeUnmount} from 'vue'
import {request} from '@/request'
type Item={id:string;version:number;kind:'stroke'|'raster';deleted?:boolean;points?:{x:number;y:number}[];color?:string;width:number;height?:number;x?:number;y?:number;png?:string}
const props=defineProps<{pictureId:string;editable:boolean}>()
const room=ref<any>(),held=ref<{epoch:number;sessionId:string;until:number}>(),busy=ref(false),error=ref(''),pending=ref<any>(),canvas=ref<HTMLCanvasElement>(),selected=ref(''),tool=ref('brush'),color=ref('#ff5b45'),width=ref(8),text=ref('')
const clientId=crypto.randomUUID(),path=`/api/picture/${props.pictureId}/collaboration/annotations`
let stopped=false,timer:ReturnType<typeof setTimeout>|undefined,lastBase='',base:HTMLImageElement|undefined,drawGeneration=0,polling=false
let active:Item|undefined,drag:{item:Item;start:{x:number;y:number}}|undefined
const rasterCache=new Map<string,HTMLImageElement>()
const visibleObjects=computed<Item[]>(()=>room.value?.state?.objects.filter((o:Item)=>!o.deleted)||[])
const canEdit=computed(()=>props.editable&&!!held.value&&!busy.value&&!pending.value)
async function api(url:string,body?:unknown){const r=await request(url,{method:body?'POST':'GET',data:body});if(r.data.code!==0)throw Error(r.data.message||'共享画布请求失败');return r.data.data}
async function accept(value:any){
  if(stopped)return
  const old=room.value
  if(old&&(BigInt(value.sessionId)<BigInt(old.sessionId)||value.sessionId===old.sessionId&&(value.revision<old.revision||value.revision===old.revision&&value.serverTime<old.serverTime)))return
  room.value=value
  const member=value.members.find((m:any)=>m.clientId===clientId)
  if(held.value&&(!member||Number(member.epoch)!==held.value.epoch||value.sessionId!==held.value.sessionId)){held.value=undefined;error.value='多人编辑资格已失效，请重新加入。本地操作仍保留。'}
  const source=value.state.baseAssetId?`/api/picture/${props.pictureId}/collaboration/assets/${value.state.baseAssetId}/content`:`/api/picture/${props.pictureId}/collaboration/canvas?revision=${value.revision}`
  if(source!==lastBase){
    const blob=(await request.get<Blob>(source,{responseType:'blob'})).data,url=URL.createObjectURL(blob),image=new Image()
    try{image.src=url;await image.decode();if(stopped||room.value.sessionId!==value.sessionId||room.value.state.baseAssetId!==value.state.baseAssetId)return;base=image;lastBase=source;if(canvas.value){canvas.value.width=image.naturalWidth;canvas.value.height=image.naturalHeight}}finally{URL.revokeObjectURL(url)}
  }
  await draw()
}
async function poll(){if(polling||busy.value)return;polling=true;try{await accept(await api(path))}catch(e){error.value=(e as Error).message}finally{polling=false}}
async function join(){if(busy.value)return;busy.value=true;try{const value=await api(path+'/lease',{action:'acquire',clientId});held.value={epoch:Number(value.epoch),sessionId:String(value.sessionId),until:Date.now()+60000};error.value='';await accept(value)}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function leave(){if(busy.value)throw Error('正在保存或交接，请稍候');if(pending.value)throw Error('请先处理本地待保存操作');const current=held.value;if(!current)return;busy.value=true;try{const value=await api(path+'/lease',{action:'release',clientId,...current});held.value=undefined;await accept(value)}finally{busy.value=false}}
function context(){if(!held.value)throw Error('请先加入多人画布');return {clientId,sessionId:held.value.sessionId,epoch:held.value.epoch,idempotencyKey:crypto.randomUUID()}}
async function submit(operations:any[]){if(!canEdit.value)return;pending.value={...context(),operations};await retry()}
async function retry(){if(busy.value||!pending.value)return;busy.value=true;try{const result=await api(path+'/operations',pending.value);pending.value=undefined;error.value='';await accept(result)}catch(e){error.value=(e as Error).message;await draw()}finally{busy.value=false}}
async function adoptLocal(){if(!held.value||!pending.value)return;const operations=structuredClone(JSON.parse(JSON.stringify(pending.value.operations)));for(const op of operations){const latest=room.value.state.objects.find((o:Item)=>o.id===op.id);if(latest?.deleted){error.value='对象已删除，请放弃该操作后重新创建';return}op.expectedVersion=latest?.version||0}pending.value={...context(),operations};await retry()}
function discard(){pending.value=undefined;error.value='';void poll();void draw()}
async function draw(){
  const token=++drawGeneration,c=canvas.value;if(!c||!base)return
  const items=visibleObjects.value.map(o=>drag?.item.id===o.id?drag.item:o)
  if(active)items.push(active)
  if(pending.value)for(const op of pending.value.operations){const at=items.findIndex(o=>o.id===op.id);if(at>=0)items.splice(at,1);if(!op.delete)items.push({...op.value,id:op.id})}
  await Promise.all(items.filter(o=>o.kind==='raster').map(async o=>{if(!rasterCache.has(o.png!)){const image=new Image();image.src=o.png!;await image.decode();rasterCache.set(o.png!,image)}}))
  if(stopped||token!==drawGeneration)return
  const g=c.getContext('2d')!;g.clearRect(0,0,c.width,c.height);g.drawImage(base,0,0)
  for(const o of items){g.save();if(o.kind==='stroke'){g.strokeStyle=o.color!;g.lineWidth=o.width*c.width;g.lineCap='round';g.lineJoin='round';g.beginPath();o.points!.forEach((p,i)=>i?g.lineTo(p.x*c.width,p.y*c.height):g.moveTo(p.x*c.width,p.y*c.height));if(o.points!.length===1)g.lineTo(o.points![0]!.x*c.width+.1,o.points![0]!.y*c.height+.1);g.stroke()}else{const w=o.width*c.width,h=o.height!*c.height;g.drawImage(rasterCache.get(o.png!)!,o.x!*c.width-w/2,o.y!*c.height-h/2,w,h)}g.restore()}
}
function point(e:PointerEvent){const r=canvas.value!.getBoundingClientRect();return {x:Math.max(0,Math.min(1,(e.clientX-r.left)/r.width)),y:Math.max(0,Math.min(1,(e.clientY-r.top)/r.height))}}
function start(e:PointerEvent){if(!canEdit.value||e.button!==0)return;canvas.value!.setPointerCapture(e.pointerId);const p=point(e);if(tool.value==='brush')active={id:crypto.randomUUID(),version:0,kind:'stroke',color:color.value,width:width.value/canvas.value!.width,points:[p]};else{const hit=[...visibleObjects.value].reverse().find(o=>o.kind==='raster'&&Math.abs(p.x-o.x!)<o.width/2&&Math.abs(p.y-o.y!)<o.height!/2);selected.value=hit?.id||'';if(hit)drag={item:structuredClone(JSON.parse(JSON.stringify(hit))),start:p}}void draw()}
function move(e:PointerEvent){if(!canEdit.value)return;const p=point(e);if(active&&active.points!.length<2000)active.points!.push(p);if(drag){const original=visibleObjects.value.find(o=>o.id===drag!.item.id);if(original){drag.item.x=Math.max(0,Math.min(1,original.x!+p.x-drag.start.x));drag.item.y=Math.max(0,Math.min(1,original.y!+p.y-drag.start.y))}}void draw()}
function end(){const item=active||drag?.item;active=undefined;drag=undefined;if(item)void submit([{id:item.id,expectedVersion:item.version,value:item}]);void draw()}
function cancel(){active=undefined;drag=undefined;void draw()}
function addText(){const value=text.value.trim();if(!value)return;addRaster(value,false);text.value=''}
function addRaster(value:string,sticker:boolean){if(!canEdit.value||!canvas.value)return;const c=document.createElement('canvas');c.width=512;c.height=128;const g=c.getContext('2d')!;g.font=`${sticker?'':'700 '}72px Microsoft YaHei, sans-serif`;g.textAlign='center';g.textBaseline='middle';g.fillStyle=color.value;g.fillText(value,256,64,490);const w=sticker?.18:.5,h=w/4*canvas.value.width/canvas.value.height;void submit([{id:crypto.randomUUID(),expectedVersion:0,value:{kind:'raster',png:c.toDataURL('image/png'),x:.5,y:.5,width:w,height:Math.min(1,h)}}])}
function removeSelected(){const item=visibleObjects.value.find(o=>o.id===selected.value);if(item)void submit([{id:item.id,expectedVersion:item.version,delete:true}])}
onMounted(async()=>{await poll();const tick=async()=>{if(stopped)return;const current=held.value;if(current&&Date.now()>current.until-40000&&!busy.value){try{const value=await api(path+'/lease',{action:'renew',clientId,...current});if(held.value===current){held.value={...current,until:Date.now()+60000};await accept(value)}}catch(e){if(held.value===current){held.value=undefined;error.value=(e as Error).message}}}await poll();if(!stopped)timer=setTimeout(tick,1500)};timer=setTimeout(tick,1500)})
onBeforeUnmount(()=>{stopped=true;clearTimeout(timer);if(held.value)void api(path+'/lease',{action:'release',clientId,...held.value}).catch(()=>{});rasterCache.clear()})
defineExpose({leave,hasPending:()=>!!pending.value,isBusy:()=>busy.value})
</script>
<style scoped>
.room-toolbar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:12px 0}.room-toolbar span{color:#718096}.room-toolbar input[type=range]{width:90px}.annotation-room canvas{display:block;max-width:100%;max-height:65vh;margin:16px auto;background:repeating-conic-gradient(#eee 0% 25%,white 0% 50%) 50%/20px 20px;touch-action:none;cursor:crosshair}.object-list{display:flex;gap:6px;flex-wrap:wrap;max-height:100px;overflow:auto}
</style>
