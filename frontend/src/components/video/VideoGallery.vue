<template>
  <section class="video-gallery">
    <div class="gallery-toolbar">
      <div><h2>视频图库</h2><p>从素材到剪辑，保留每一次创作。</p></div>
      <a-space><a-button @click="reload">刷新</a-button><a-button v-if="canUpload" @click="create">新建剪辑项目</a-button><a-button v-if="canUpload" type="primary" :loading="busy" @click="input?.click()">上传视频</a-button></a-space>
      <input ref="input" type="file" accept="video/mp4,video/quicktime,video/webm,.mov" hidden @change="upload" />
    </div>
    <a-alert v-if="error" type="error" :message="error" show-icon closable @close="error=''" />
    <a-spin :spinning="loading||busy">
      <a-empty v-if="!records.length&&!loading" description="暂无视频，上传一个素材开始剪辑" />
      <div class="video-grid"><router-link v-for="item in records" :key="item.id" :to="`/video/${item.id}`" class="video-card"><div class="video-poster"><img :src="`${videoBase}/videos/${item.id}/media?kind=poster`" :alt="item.name" /><span class="play-mark">▶</span><span class="duration">{{frameTime(item.metadata.frames,item.metadata.fps)}}</span></div><div class="video-caption"><strong>{{item.name}}</strong><span>{{item.metadata.width}} × {{item.metadata.height}} · {{item.review_status===0?'待审核':item.review_status===2?'未通过':'已保存'}}</span></div></router-link></div>
    </a-spin>
    <div v-if="projects.length" class="drafts"><h3>继续编辑项目</h3><a-space wrap><router-link v-for="item in projects" :key="item.id" :to="`/video/editor/${item.id}`"><a-button>{{item.name}} · v{{item.revision}}</a-button></router-link></a-space></div>
  </section>
</template>
<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {videoApi,videoBase,frameTime,type VideoRecord} from '@/api/videoEditor'
const props=withDefaults(defineProps<{spaceId?:string;canUpload?:boolean}>(),{canUpload:true})
const router=useRouter(),records=ref<VideoRecord[]>([]),projects=ref<any[]>([]),loading=ref(false),busy=ref(false),error=ref(''),input=ref<HTMLInputElement>()
async function reload(){loading.value=true;error.value='';try{const query=props.spaceId?`?spaceId=${props.spaceId}`:'';[records.value,projects.value]=await Promise.all([videoApi<VideoRecord[]>('/videos'+query),videoApi<any[]>('/projects'+query)])}catch(e){error.value=(e as Error).message}finally{loading.value=false}}
async function create(){if(busy.value)return;busy.value=true;try{const project=await videoApi('/projects',{name:'未命名视频',spaceId:props.spaceId||null});await router.push(`/video/editor/${project.id}`)}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function upload(event:Event){const file=(event.target as HTMLInputElement).files?.[0];if(!file)return;busy.value=true;error.value='';try{
 const capabilities=await videoApi('/capabilities'),maxFrames=Math.floor(capabilities.limits.projectSeconds*24)
 const project=await videoApi('/projects',{name:file.name.replace(/\.[^.]+$/,''),spaceId:props.spaceId||null}),sessionId=crypto.randomUUID(),lease=await videoApi(`/projects/${project.id}/lease`,{action:'acquire',sessionId})
 const renew=setInterval(()=>videoApi(`/projects/${project.id}/lease`,{action:'renew',sessionId,epoch:lease.epoch}).catch(()=>{}),20000)
 try{
 const form=new FormData();form.append('file',file);const asset=await videoApi(`/projects/${project.id}/assets`,form)
 const edit=await videoApi(`/projects/${project.id}/operations`,{expectedRevision:1,sessionId,epoch:lease.epoch,idempotencyKey:crypto.randomUUID(),operations:[{type:'insert_clip',assetId:asset.id,trackId:'visual',at:0,length:Math.min(asset.frames,maxFrames)}]})
 await videoApi('/videos',{projectId:project.id,revision:edit.revision,assetId:asset.id,name:project.name,expectedRevision:edit.revision,sessionId,epoch:lease.epoch,idempotencyKey:crypto.randomUUID()})
 }finally{clearInterval(renew);await videoApi(`/projects/${project.id}/lease`,{action:'release',sessionId,epoch:lease.epoch}).catch(()=>{})}await reload()
 }catch(e){error.value=(e as Error).message}finally{busy.value=false;(event.target as HTMLInputElement).value=''}}
onMounted(reload);watch(()=>props.spaceId,reload)
</script>
<style scoped>
.gallery-toolbar{display:flex;align-items:center;justify-content:space-between;margin:18px 0 24px;gap:16px}.gallery-toolbar h2{margin:0;font-size:24px}.gallery-toolbar p{margin:6px 0;color:#737b89}.video-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:24px}.video-card{color:inherit;border:1px solid #e3e7ec;border-radius:12px;overflow:hidden;background:white;transition:transform .15s}.video-card:hover{transform:translateY(-3px)}.video-poster{aspect-ratio:16/9;background:#161b23;position:relative;overflow:hidden}.video-poster img{width:100%;height:100%;object-fit:cover}.play-mark{position:absolute;inset:0;display:grid;place-content:center;color:white;font-size:28px;text-shadow:0 2px 12px #000}.duration{position:absolute;bottom:10px;right:10px;background:#000b;color:white;padding:2px 7px;border-radius:4px;font-size:12px}.video-caption{padding:16px;display:flex;flex-direction:column;gap:8px}.video-caption span{font-size:12px;color:#77808f}.drafts{margin-top:36px;padding-top:22px;border-top:1px solid #e8e8e8}@media(max-width:700px){.gallery-toolbar{flex-direction:column;align-items:flex-start}}
</style>
