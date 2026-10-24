# AI 协同云影库

面向图片、视频与 AI 创作的多人协同资产平台。

`Spring Boot` · `Vue 3` · `WebSocket` · `Redis` · `MySQL` · `FastAPI + ChromaDB` · `Node.js + FFmpeg`

> 以下截图均来自当前运行系统的真实浏览器，窗口尺寸为 `1920 × 1080`。

## 资产图库

### 图库总览

![当前图库总览](README-assets/01-gallery.png)

### 智能向量以图搜图

![当前向量检索与相似图片召回](README-assets/02-vector-search.png)

### 团队空间与权限

![当前团队空间、成员管理和空间分析入口](README-assets/03-team-space.png)

## 图片协同编辑

### 双页面协同

<table>
  <tr>
    <th>Alice 编辑者页面</th>
    <th>Bob 协作者页面</th>
  </tr>
  <tr>
    <td><img src="README-assets/04-picture-alice.png" alt="Alice 图片协同编辑页面" width="100%"></td>
    <td><img src="README-assets/05-picture-bob.png" alt="Bob 图片协同查看页面" width="100%"></td>
  </tr>
</table>

### AI 图片编辑 Agent

![当前图片编辑 Agent 工作台](README-assets/06-image-agent.png)

## 视频协同工作台

![当前视频时间线、素材轨道、播放预览与剪辑 Agent](README-assets/07-video-workbench.png)

## 后台与工程能力

### 空间图库分析

![当前空间存储、图片数量和分类统计](README-assets/08-analytics.png)

### 本机运维控制台

![当前 MySQL、Redis、腾讯云 COS 与本地向量服务状态](README-assets/09-ops-console.png)
