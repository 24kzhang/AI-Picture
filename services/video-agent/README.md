# 视频展示与剪辑服务

独立 TypeScript / Fastify 服务。Java 继续验证现有登录 Cookie、空间角色与 COS 对象访问；Vue 提供视频图库、详情和剪辑工作台。Hypit 固定为 **0.2.16**，负责真实编译、Studio 预览、画面渲染与音频合成。生成素材仅使用已有自建模型接口，对话规划使用 OpenAI 兼容服务。

## 启动

Windows 在仓库根目录运行 `./scripts/start-video.ps1`，或 `./scripts/start-all.ps1`。脚本读取被忽略的 `runtime-data/start-env.ps1`，默认启动在 `127.0.0.1:8091`；Java 地址跟随 `SERVER_PORT`。首次安装使用 npm 镜像。FFmpeg 必须为 6+：Windows 优先复用本机 FFmpeg，否则通过 codex conda 环境的 imageio-ffmpeg 安装；ffprobe 使用锁定的可选 Windows 依赖。

Linux 安装 Node 22.15+、FFmpeg 6+、ffprobe、中文字体以及 Chromium 的系统运行库，按 `.env.example` 设置环境变量后运行：

```sh
npm ci --registry=https://registry.npmmirror.com
npm run migrate
npm start
```

环境变量需要由进程管理器或 Shell 注入，服务不自动加载 `.env`。数据库沿用现有 MySQL；迁移新增 `video_*` 表、修正旧视频表的事务引擎，并可重复执行。连接时统一 MySQL 会话为 UTC；旧版本默认创建时间曾使用数据库本地时钟，第一次升级会按原数据库时差备份并校正视频创建时间，未更新过的同值更新时间一并校正，租约和业务内容保持原样。校正与 `video_migration` 标记在同一事务完成；备份位于 `VIDEO_DATA_ROOT/legacy-clock-backup-*.json`。Hypit Runtime 的 SQLite 和浏览器缓存保存在 `runtime-data/video-agent`；不承担项目业务持久化。首次导出会下载 Hypit 指定的渲染引擎和 Headless Shell，使用 npm/浏览器镜像；也可通过 `VIDEO_CHROME` 指定已安装浏览器。

`VIDEO_SERVICE_KEY` 必须在 Java 和 Node 中相同；本地未配置时使用自动生成的 `runtime-data/video-service.key`。Java 的 `gallery.video.service-key-file` 可指定文件位置。`VIDEO_LLM_KEY` 独立覆盖规划密钥，未设置时复用现有阿里云配置。数据库、模型地址、上传限制、空间配额、字体和可选浏览器路径见 `.env.example`。

前端 `VITE_VIDEO_ENABLED=false` 可隐藏视频入口；启动器 `VIDEO_ENABLED=false` 可关闭视频服务启动。开发代理已配置 `/api/video-editor/**`；生产反向代理模板见 `deploy/nginx-video.conf`，包含 WebSocket、SSE、Range 与上传限制。服务及 Studio 端口保持 loopback，不直接暴露。

## 使用与事实源

首页/空间页选择“视频”，可上传 MP4/MOV 或新建项目。工作台支持素材拖入、多轨移动、两端裁剪、分割、复制删除、音画解除关联、轨道排序、标题字幕、布局、音量/静音/淡入淡出、横竖画幅、撤销重做及历史恢复。左侧生成面板将产物保存到素材库；对话编辑只执行已定义的结构化工具。

基础剪辑项目时钟为 24 fps，操作使用整数帧、左闭右开区间。每个业务版本保存完整 SVML/SVS/SVRun 源集合及素材引用，SVML 中包含编辑元数据；时间线是该版本的派生视图。修改经过权限、租约、预期版本、确定性操作、Hypit 编译和数据库提交。恢复历史生成新版本。移动保留空隙；左裁剪改变素材入点；默认联动原声及关联字幕。

## 完整 Hypit 工程

工作台点击“打开完整 Hypit 工程”，在 `/video/hypit/:projectId` 使用固定版本的原生 Studio、源码、语义时间线、参数、评论、任务与产物。基础工程需要先获取编辑权并“开启完整工程”。独立工程视图包括源文件、组件目录、八个模板、构建产物和历史/环境；Studio 占用完整宽度，窄屏可横向滚动访问属性。时间线片段支持鼠标或 Enter/空格选择。原生源码修改与属性写入都经过相同的业务版本、租约和编译门禁。

完整工程的 `files` 是事实源，允许最多 32 个受控 SVML/SVS/SVRun/JSON 文件（合计 1 MiB），支持中文嵌套目录。只接受项目素材、固定 Hypit 内置包和服务提供的可信扩展；不接受任意用户 JS/TS、远程资源或运行配置。复杂源不压平为基础剪辑 JSON。项目状态仅作提示，实际渲染使用源工程自己的时钟；已验证 24/30 fps 成片及图库时长。

原生 Script/WhisperX/Caption Fine 可以使用真实配音完成字级字幕。自建 ASR Endpoint 通过 Hypit 公共 SDK 注册，提交回执持久化；字幕模板按阅读片段分 Cue，并保留实际语音内容及单词边界。其他自建生图、生视频和配音通过现有 Agent 工具生成并存为素材后加入工程。对话模型可查询组件 vocabulary、检查完整源和提交经编译验证的候选源。

SVRun 可以有多个 target：视频、PNG、48 kHz 立体声 WAV，以及保留 `value.json` 和相对资源路径的复合产物 ZIP。只有直接视频 Output 可作为图库成片。构建固定不可变版本；`build-record/satisfy` 显式复用已归属本项目的产物。共享 Results 目录只供 Runtime 存储，所有 Studio、媒体、日志和源 Build 引用仍做项目归属检查。克隆工程在相同授权存储范围内创建独立素材引用、版本和编辑权，并显式继承可复用 Build。

评论创建、编辑、解决及重开保存在版本源的 feedback 中；历史恢复形成新版本。精确帧截图调用原生 snapshot，执行期间版本发生变化则拒绝保存错标结果。Node 服务重启后重新加载预览可恢复 Studio 会话，业务版本、素材、评论和 Build 回执仍保留。

这是固定版本原生能力的图库集成。上游禁用的表情/画面标注按钮不会伪装成已实现，语义 Cue 时间保留为只读证据；商业模型专属生成参数和任意已有视频局部重绘未由自建模型提供。128 个包的目录与语法可查，代表性组件完成真实渲染，不表示逐个包的所有参数都已验收。

关联字幕可以单独移动、裁剪、分割、复制、删除；这些操作不会修改源视频或原声，复制后的字幕独立。操作源媒体仍联动其关联字幕，延长源媒体不会拉长已对齐的字幕。文字的大小通过字号调整，输出宽高必须为偶数。非空项目按实际终点导出，允许不足一秒；安装时对固定 Hypit 0.2.16 应用单帧合成兼容补丁，仍严格校验帧数、尺寸和时间戳。撤销/重做只有服务器恢复成功才移动历史记录；任务历史可切换查看先前的生成与导出结果。预览画布按容器尺寸等比适配横屏/竖屏。

字幕以 `sourceClipId` 明确引用来源媒体，解除音画联动保留字幕与各自来源的关联；复制及分割会重映射该引用，旧版本的仅分组关联在下一次编辑时补齐。重新转写会替换该来源（及仍联动的原声）的旧字幕，不产生重复。局部配音替换会删除对应原声内容的区间内旧字幕，保留区间外字幕和已经独立移动的其他内容。Agent 的完成提示由已提交版本和工具执行回执生成，避免规划模型的总结与实际修改结果矛盾。

同一轨道中时间区间重叠的片段分行显示，保持业务轨道和渲染顺序不变，避免标题覆盖转写字幕后误选片段。时间线片段支持 Tab 聚焦、Enter / Space 选择。Windows 创建 Hypit 结果目录时，仅对同一次原子重命名的 `EPERM` / `EBUSY` 做最多四次递增延迟重试；永久错误继续失败，不重试模型提交或重新生成 Build。

单页 TIFF 按图片导入，JPEG 等图片先应用 EXIF 方向，再记录转换后的尺寸。带封面的 MP3 排除封面流，音频统一为 48 kHz 立体声；动态图按视频规范化。损坏或不支持的媒体返回明确的 `INVALID_MEDIA`，不建立素材记录。Hypit 补丁同时保留单帧视频容器的移动画面类型，支持成片再次导入编辑；静态图和封面图仍保持原来的分类。

视频导入先确定规范化画面的实际帧数，再将音频补齐/裁至该帧数对应的 48 kHz 样本范围后合成浏览器 MP4，避免无限 `apad` 与 `-shortest` 产生多余音频尾部。`repairVideoPlayback` 为旧导入素材增加独立 `browserObjectKey` 播放版本，并更新直接上传的视频记录；原对象、音频、源文件及历史项目版本继续保留，用于不可变导出。

编辑租约 60 秒、20 秒续租，包含会话 UUID 与递增 epoch。交接只允许已有编辑权限的成员。Studio 原生 mutation 经过同一提交入口；任意源文件写入和其他写接口被拒绝。原生时间、位置、字号、颜色、适配、音量及淡入淡出修改映射为确定性操作，未接入的原生参数保持只读。完整拖拽、分割、复制、跨轨移动和历史恢复由外层时间线提供。补丁在安装和启动 Studio 时校验固定版本，保留原界面名称、Logo 和版权。

导出固定不可变版本；导出期间继续编辑不改变成片。成片 H.264/AAC、yuv420p、48 kHz 立体声、faststart，检查帧数和音画时长后上传 COS。保存后可从视频详情再次打开原项目。公共成片遵循审核可见性；公共草稿仅作者/管理员可访问，团队空间复用现有角色。

## 模型与恢复

SDK 的项目 Model/Provider 位于 `src/model-sdk.ts`，媒体模型不会冒用商业模型名；ASR 返回独立 Transcript 类型。HTTP 适配位于 `src/providers.ts`：Qwen JSON 生图/重复 image multipart 编辑；LTX wait=false、参考图登记、尺寸/帧数约束；IndexTTS 音色或参考音频；ASR 对实际使用音频做中文转写与对齐。缺失时间戳不生成虚假字幕，部分字级时间退回句级精度。生成的配音默认进入配音轨；插入配音会拆分并静音对应区间的原声，保留区间外原声及正确素材入点，删除该区间的原声旧字幕。工具参数 `replaceOriginal:false` 可明确保留原声混音；之后也可在原声音轨属性中取消静音。背景音乐不会默认覆盖原声。

远端 taskId/本地 buildId 在轮询前持久化，结果检查后立即保存 COS。429 使用 Retry-After；提交未收到回执标记 unknown，禁止自动重发；404 标记任务丢失。取消停止后续步骤，不调用远端 DELETE 冒充停止推理。任务步骤保留请求摘要、回执、结果和错误。

服务重启将排队/执行任务标记为待重新授权；Cookie 和模型密钥不写入任务数据库。原发起人获取当前会话的编辑权后可恢复已有回执查询/产物收集。Agent 原版本已变化时必须选择“重新规划”，使用当前版本并提示复用已有生成素材。未完成 Hypit Build 需明确新建任务；界面恢复失败提示后可新建导出，已生成素材继续复用。生成期间版本或租约变化时保留素材，拒绝覆盖新版本。

视频配额独立：默认唯一 COS 对象 1 GiB、正式视频 50 条、单素材 100 MiB、时间线 120 秒。删除正式视频保留项目和素材供继续编辑，因此保留对象仍计入字节配额。任务在本地队列顺序执行，Hypit 使用两个渲染浏览器进程。

## 验证

`npm run build` 校验类型；`npm test` 覆盖时间线语义、租约代次、Model 约束与任意命令拒绝。`test/integration-acceptance.ts` 显式连接本地 Java/Node/MySQL，故障注入只替换测试进程的网络调用，验证重复提交、编译失败、429、未知提交、丢失任务、断连恢复、取消、COS 回滚和真实空间角色/交接。真实模型测试为 `test/model-acceptance.ts`；夹具由 `test/fixtures.ts` 生成。测试账号与回执读取忽略目录 `runtime-data/video-acceptance`，不包含在源码中。

最终场景验收使用运动中的森林溪流、街市行人、咖啡店摄影画面、完整中文口播、字幕和音乐。`test/scene-acceptance.ts` 准备真实场景；`test/scene-variants-acceptance.ts` 另验真实参考图编辑、图生视频、配音区间替换及串联导出；`test/live-concurrency-acceptance.ts` 验真实生成期间的手动版本保护；`test/media-acceptance.ts` 对成片做镜头边界、帧数及 PCM 混音对照；`test/restart-acceptance.ts prepare/verify` 配合实际进程重启验收新会话恢复。纯色夹具只用于边界诊断，不作为最终场景验收。详细浏览器证据、导出探测和故障报告保存在忽略目录。

`test/editor-regression-acceptance.ts` 使用已保留的真实森林镜头与中文口播，验证字幕独立编辑、真实 qwen-plus 请求以及短片导出；`VIDEO_SHORT_FRAMES=1` 可检查单帧导出，`VIDEO_ACCEPTANCE_REPORT` 可分别保留报告。`test/repair-playback-acceptance.ts` 显式维护验收账号自己的旧视频播放版本，并检查项目源版本保持原样。

`test/formats-acceptance.ts` 使用真实咖啡馆照片、森林视频和中文口播，验证 TIFF、带方向信息的 JPEG、动态 GIF、带封面的单声道 MP3、损坏文件及单帧 MP4 的再次导出。`test/combinations-acceptance.ts` 验证解除联动、来源字幕的分割复制、两次真实 Agent / 自建 ASR 转写及十秒组合成片；`test/browser-combinations-check.ts` 用接口状态检查补充浏览器真实点击的移动、撤销及转写验收。

本集成用于个人自用和演示。Hypit 的许可原文见 `LICENSE.hypit` 和 `NOTICE`；对外提供多租户托管功能需要重新确认授权。

完整工程专项验收脚本：`test/hypit-full-acceptance.ts`（实景旅行/双画面/竖屏、多文件、失败回滚、真实 Agent）、`test/hypit-components-acceptance.ts`（排名/卡片/评论、真实中文 ASR 与逐字字幕）、`test/hypit-management-acceptance.ts`（变体、幂等、项目隔离、多产物、Build 复用和精确帧截图）、`test/hypit-composite-acceptance.ts`（30 fps、复合 ZIP 与图库帧率）。均依赖已经启动的 Java/Node/COS 和忽略目录中的验收账号、真实素材，不能把缺少依赖的跳过当成通过。执行证据见 `runtime-data/video-acceptance/HYPIT-FULL-ACCEPTANCE-20261003.md`。

`test/clock-acceptance.ts` 验证多个实连的 UTC 会话、旧记录校正、重复迁移幂等、新项目创建时间及租约获取/续租/释放/旧会话拒绝。旧时间校正以升级时数据库原会话时差为依据；曾手工指定不同日期或跨 DST 时差的历史数据，应按备份单独核对。
