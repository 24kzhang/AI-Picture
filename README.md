# AI 协同云影库

面向图片、视频与 AI 创作的多人协同资产平台。

`Spring Boot` · `Vue 3` · `WebSocket` · `Redis` · `MySQL` · `FastAPI + ChromaDB` · `Node.js + FFmpeg`

## 资产图库

### 图库总览

![图库总览](README-assets/01-gallery.png)

### 以图搜图

![向量检索与相似图片召回](README-assets/02-vector-search.png)

### 团队空间

![团队空间与图片权限](README-assets/03-team-space.png)

## 图片协同编辑

### 双页面在线协同

![两个浏览器页面同时进入协同图片工作台](README-assets/04-collab-online.png)

### 画笔、文字与图层实时同步

![左侧页面编辑后，右侧页面同步看到相同画布结果](README-assets/05-collab-sync.png)

### 编辑权交接

![协作者之间交接编辑权并继续修改画布](README-assets/06-collab-handoff.png)

### AI 图片编辑 Agent

![自然语言指令驱动图片编辑](README-assets/07-ai-editor.png)

## 视频协同工作台

<table>
  <tr>
    <th>编辑者页面</th>
    <th>协作者页面</th>
  </tr>
  <tr>
    <td><img src="README-assets/10-video-owner.png" alt="视频协同编辑者页面" width="100%"></td>
    <td><img src="README-assets/11-video-peer.png" alt="视频协同协作者页面" width="100%"></td>
  </tr>
</table>

## 后台与工程能力

### 空间图库分析

![存储、图片数量、分类与使用分析](README-assets/08-analytics.png)

### 连接与运行配置

![MySQL、Redis、腾讯云 COS 与本地向量服务状态](README-assets/09-ops-console.png)
