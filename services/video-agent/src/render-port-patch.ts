import {ApiError} from './errors.js'

// Chromium net/base/port_util.cc, kRestrictedPorts. Keep browser restrictions intact.
const restricted=[0,1,7,9,11,13,15,17,19,20,21,22,23,25,37,42,43,53,69,77,79,87,95,101,102,103,104,109,110,111,113,115,117,119,123,135,137,139,143,161,179,389,427,465,512,513,514,515,526,530,531,532,540,548,554,556,563,587,601,636,989,990,993,995,1719,1720,1723,2049,3659,4045,5060,5061,6000,6566,6665,6666,6667,6668,6669,6697,10080]
export const renderServerNeedle='    server = await createFileServer({ projectDir: work, port: 0, fps,\n      preHeadScripts: [hyperframesFrameSelectionPrelude(requestedRanges)] });'

export function patchRenderPortSource(source:string) {
  const marker='gallery-browser-safe-render-port-v1'
  if(source.includes(marker))return source
  if(!source.includes(renderServerNeedle))throw new ApiError(500,'HYPIT_PATCH_FAILED','渲染预览端口补丁定位失败')
  return source.replace(renderServerNeedle,`    // ${marker}: bind again before rendering if the OS assigns a Chromium-blocked port.
    const galleryBlockedPorts = new Set(${JSON.stringify(restricted)});
    for (let galleryAttempt = 0; ; galleryAttempt += 1) {
      server = await createFileServer({ projectDir: work, port: 0, fps,
        preHeadScripts: [hyperframesFrameSelectionPrelude(requestedRanges)] });
      if (!galleryBlockedPorts.has(Number(new URL(server.url).port || "80"))) break;
      await server.close();
      server = undefined;
      if (galleryAttempt >= 31) throw new Error("无法分配浏览器可用的渲染端口");
    }`)
}
