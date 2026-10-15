/** Keep account/configuration failures actionable without publishing arbitrary upstream response text. */
export function plannerFailureMessage(status:number,body:string):string {
  let code=''
  try {const value=JSON.parse(body);code=String(value.error?.code||value.error?.type||value.code||'').toLowerCase()} catch {}
  if(code.includes('arrearage')||code.includes('insufficient_quota'))return '规划模型账号欠费或额度不足，请充值或更换可用的模型配置；现有素材和手动剪辑仍可使用'
  if(status===401||code.includes('invalidapikey')||code.includes('invalid_api_key'))return '规划模型密钥无效，请在管理页更新模型配置'
  if(status===403)return '规划模型访问被拒绝，请检查账号权限和模型配置'
  if(status===429)return '规划模型请求受限，请稍后重试'
  return `对话规划失败（HTTP ${status}），请检查模型配置或稍后重试`
}
