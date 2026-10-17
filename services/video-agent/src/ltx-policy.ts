// The deployed LTX model can produce corrupt frames beyond this single-clip limit.
export const LTX_MAX_FRAMES = 257
export const LTX_FRAME_RATE = 24
export const LTX_CLIP_GUIDANCE = 'LTX 单段最多 257 帧（24fps，约 10.7 秒）；更长视频需分段生成后拼接，不能仅在提示词中要求延长时长。'
