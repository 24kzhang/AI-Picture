(() => {
  'use strict'

  const API_ROOT = new URL('../local-admin/', window.location.href).pathname.replace(/\/$/, '')
  const state = {
    overview: {},
    tables: [],
    selectedTable: '',
    columns: [],
    primaryKeys: [],
    rows: [],
    page: 1,
    pageSize: 20,
    total: 0,
    rowMode: 'insert',
    editingPrimaryKey: {},
    dedupe: null,
  }

  const $ = (selector, root = document) => root.querySelector(selector)
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector))

  async function api(path, options = {}) {
    const response = await fetch(API_ROOT + path, {
      credentials: 'same-origin',
      headers: options.body ? { 'Content-Type': 'application/json' } : {},
      ...options,
    })
    let payload
    try {
      payload = await response.json()
    } catch (_) {
      throw new Error(`服务返回了无法识别的内容（HTTP ${response.status}）`)
    }
    if (!response.ok || payload.code !== 0) {
      throw new Error(payload.message || `请求失败（HTTP ${response.status}）`)
    }
    return payload.data
  }

  let toastTimer
  function toast(message, error = false) {
    const element = $('#toast')
    element.textContent = message
    element.classList.toggle('error', error)
    element.classList.add('show')
    clearTimeout(toastTimer)
    toastTimer = setTimeout(() => element.classList.remove('show'), 3600)
  }

  function setBusy(element, busy, busyText) {
    if (!element) return
    element.classList.toggle('loading', busy)
    element.disabled = busy
    if (busy) {
      if (busyText) {
        element.dataset.originalText = element.textContent
        element.textContent = busyText
      }
    } else if (element.dataset.originalText) {
      element.textContent = element.dataset.originalText
      delete element.dataset.originalText
    }
  }

  function displayValue(value) {
    if (value === null) return 'NULL'
    if (value === undefined) return ''
    if (typeof value === 'object') return JSON.stringify(value)
    return String(value)
  }

  function cell(tag, text, className) {
    const element = document.createElement(tag)
    if (className) element.className = className
    element.textContent = text
    return element
  }

  function setTab(name) {
    $$('.nav-item').forEach((item) => item.classList.toggle('active', item.dataset.tab === name))
    $$('.panel').forEach((panel) => panel.classList.toggle('active', panel.dataset.panel === name))
    const titles = { overview: '连接与配置', database: '数据库', redis: 'Redis', dedupe: '重复图片' }
    $('#page-title').textContent = titles[name]
  }

  function statusDetail(key, data) {
    if (key === 'database') {
      return data.connected ? `${data.product || 'MySQL'} · ${data.user || ''}\n${data.url || ''}` : (data.message || '无法连接数据库')
    }
    if (key === 'redis') {
      return data.connected ? `PING 正常 · 当前库 ${data.keyCount ?? 0} 个键` : (data.message || '无法连接 Redis')
    }
    if (key === 'cos') {
      return data.connected ? `${data.region || ''} · ${data.bucket || ''}` : (data.configured ? '参数已填写，但连接检查失败' : '尚未填写完整 COS 参数')
    }
    return data.connected ? '本机 Chroma 与向量接口可用' : '向量服务未启动或百炼配置不可用'
  }

  function renderOverview() {
    const overview = state.overview
    const definitions = [
      ['database', 'MySQL'], ['redis', 'Redis'], ['cos', '腾讯云 COS'], ['vector', '本地向量'],
    ]
    const grid = $('#status-grid')
    grid.replaceChildren()
    definitions.forEach(([key, label]) => {
      const data = overview[key] || {}
      const ok = data.connected === true
      const card = document.createElement('article')
      card.className = `status-card${ok ? ' ok' : ''}`
      const header = document.createElement('header')
      header.append(cell('h3', label), cell('span', ok ? 'ONLINE' : 'CHECK', 'badge'))
      const detail = cell('p', statusDetail(key, data))
      detail.style.whiteSpace = 'pre-line'
      card.append(header, detail)
      grid.append(card)
    })

    const settings = overview.settings || {}
    $('#settings-path').textContent = settings.settingsFile || '本机配置文件尚未生成'
    $$('[data-setting]').forEach((input) => {
      if (input.dataset.secret === 'true') {
        input.value = ''
      } else {
        input.value = settings[input.dataset.source] ?? ''
      }
    })
    const states = [
      ['database-password-state', settings.databasePasswordConfigured],
      ['redis-password-state', settings.redisPasswordConfigured],
      ['cos-id-state', settings.cosSecretIdConfigured],
      ['cos-key-state', settings.cosSecretKeyConfigured],
      ['aliyun-key-state', settings.aliyunApiKeyConfigured],
    ]
    states.forEach(([id, configured]) => {
      $('#' + id).textContent = configured ? '· 已配置' : '· 未配置'
    })
  }

  async function loadOverview() {
    state.overview = await api('/overview')
    renderOverview()
  }

  async function saveSettings(event) {
    event.preventDefault()
    const button = $('#save-settings')
    const values = {}
    $$('[data-setting]').forEach((input) => {
      values[input.dataset.setting] = input.value.trim()
    })
    setBusy(button, true, '正在保存…')
    try {
      await api('/settings', { method: 'POST', body: JSON.stringify(values) })
      await loadOverview()
      toast('配置已保存。连接类配置请关闭启动窗口后重新双击 start.bat 生效。')
    } catch (error) {
      toast('保存配置失败：' + error.message, true)
    } finally {
      setBusy(button, false)
    }
  }

  function renderTableOptions() {
    const select = $('#table-select')
    const previous = state.selectedTable
    select.replaceChildren(new Option('选择数据表', ''))
    state.tables.forEach((table) => select.add(new Option(`${table.name}（${table.rowCount}）`, table.name)))
    if (previous && state.tables.some((table) => table.name === previous)) {
      select.value = previous
    } else {
      state.selectedTable = ''
      select.value = ''
    }
  }

  async function loadTables() {
    state.tables = await api('/database/tables') || []
    renderTableOptions()
    if (state.selectedTable) await loadRows()
  }

  function renderDatabase() {
    const table = $('#database-table')
    const head = $('thead', table)
    const body = $('tbody', table)
    head.replaceChildren()
    body.replaceChildren()
    const empty = $('#database-empty')
    const pager = $('#database-pager')

    if (!state.selectedTable) {
      empty.textContent = '选择一个数据表以查看内容'
      empty.hidden = false
      pager.hidden = true
      return
    }

    const headerRow = document.createElement('tr')
    state.columns.forEach((column) => headerRow.append(cell('th', column.name)))
    headerRow.append(cell('th', '操作'))
    head.append(headerRow)

    state.rows.forEach((row) => {
      const tr = document.createElement('tr')
      state.columns.forEach((column) => {
        const td = cell('td', displayValue(row[column.name]))
        td.title = displayValue(row[column.name])
        tr.append(td)
      })
      const actionCell = document.createElement('td')
      actionCell.className = 'table-actions'
      const edit = cell('button', '编辑', 'link-button')
      edit.type = 'button'
      edit.addEventListener('click', () => openRowDialog('edit', row))
      const remove = cell('button', '删除', 'link-button danger')
      remove.type = 'button'
      remove.disabled = state.primaryKeys.length === 0
      remove.addEventListener('click', () => deleteRow(row))
      actionCell.append(edit, remove)
      tr.append(actionCell)
      body.append(tr)
    })

    empty.textContent = state.rows.length ? '' : '当前页没有记录'
    empty.hidden = state.rows.length > 0
    pager.hidden = false
    const totalPages = Math.max(1, Math.ceil(state.total / state.pageSize))
    $('#page-label').textContent = `第 ${state.page} / ${totalPages} 页 · 共 ${state.total} 条`
    $('#previous-page').disabled = state.page <= 1
    $('#next-page').disabled = state.page >= totalPages
    const summary = state.tables.find((item) => item.name === state.selectedTable)
    $('#table-summary').textContent = summary ? `${summary.columnCount} 个字段 · ${summary.rowCount} 条记录` : ''
  }

  async function loadRows() {
    if (!state.selectedTable) return renderDatabase()
    const data = await api(`/database/rows?table=${encodeURIComponent(state.selectedTable)}&page=${state.page}&pageSize=${state.pageSize}`)
    state.columns = data.columns || []
    state.primaryKeys = data.primaryKeys || []
    state.rows = data.rows || []
    state.total = Number(data.total || 0)
    renderDatabase()
  }

  function openRowDialog(mode, row = {}) {
    state.rowMode = mode
    state.editingPrimaryKey = Object.fromEntries(state.primaryKeys.map((key) => [key, row[key]]))
    const values = mode === 'insert'
      ? Object.fromEntries(state.columns.filter((column) => !column.autoIncrement).map((column) => [column.name, null]))
      : Object.fromEntries(state.columns.map((column) => [column.name, row[column.name]]))
    $('#row-dialog-title').textContent = mode === 'insert' ? `新增 ${state.selectedTable} 记录` : `编辑 ${state.selectedTable} 记录`
    $('#row-json').value = JSON.stringify(values, null, 2)
    $('#row-dialog').showModal()
  }

  async function saveRow() {
    let values
    try {
      values = JSON.parse($('#row-json').value)
      if (!values || Array.isArray(values) || typeof values !== 'object') throw new Error('必须是 JSON 对象')
    } catch (error) {
      toast('JSON 格式错误：' + error.message, true)
      return
    }
    const path = state.rowMode === 'insert' ? '/database/row/insert' : '/database/row/update'
    const payload = { table: state.selectedTable, values }
    if (state.rowMode === 'edit') payload.primaryKey = state.editingPrimaryKey
    const button = $('#save-row')
    setBusy(button, true, '保存中…')
    try {
      await api(path, { method: 'POST', body: JSON.stringify(payload) })
      $('#row-dialog').close()
      await loadTables()
      toast(state.rowMode === 'insert' ? '数据库记录已新增' : '数据库记录已更新')
    } catch (error) {
      toast('保存记录失败：' + error.message, true)
    } finally {
      setBusy(button, false)
    }
  }

  async function deleteRow(row) {
    if (!state.primaryKeys.length) return toast('该表没有主键，不能从控制台删除', true)
    const primaryKey = Object.fromEntries(state.primaryKeys.map((key) => [key, row[key]]))
    if (!window.confirm(`确认物理删除 ${state.selectedTable} 中的记录？\n${JSON.stringify(primaryKey)}`)) return
    try {
      await api('/database/row/delete', { method: 'POST', body: JSON.stringify({ table: state.selectedTable, primaryKey }) })
      await loadTables()
      toast('数据库记录已物理删除')
    } catch (error) {
      toast('删除失败：' + error.message, true)
    }
  }

  function renderRedis(items) {
    const body = $('#redis-table tbody')
    body.replaceChildren()
    items.forEach((item) => {
      const row = document.createElement('tr')
      row.append(cell('td', item.key), cell('td', item.type), cell('td', String(item.ttl)))
      const valueCell = document.createElement('td')
      const code = cell('code', displayValue(item.value))
      valueCell.append(code)
      const actions = document.createElement('td')
      actions.className = 'table-actions'
      if (String(item.type).toLowerCase() === 'string') {
        const edit = cell('button', '编辑', 'link-button')
        edit.type = 'button'
        edit.addEventListener('click', () => openRedisDialog(item))
        actions.append(edit)
      }
      const remove = cell('button', '删除', 'link-button danger')
      remove.type = 'button'
      remove.addEventListener('click', () => deleteRedis(item.key))
      actions.append(remove)
      row.append(valueCell, actions)
      body.append(row)
    })
    const empty = $('#redis-empty')
    empty.textContent = items.length ? '' : '没有找到匹配的 Redis 键'
    empty.hidden = items.length > 0
  }

  async function loadRedis() {
    const pattern = $('#redis-pattern').value.trim() || '*'
    const data = await api(`/redis/keys?pattern=${encodeURIComponent(pattern)}&limit=100`)
    renderRedis(data.items || [])
  }

  function openRedisDialog(item) {
    const editing = Boolean(item)
    $('#redis-key').value = item?.key || ''
    $('#redis-key').disabled = editing
    $('#redis-value').value = item?.value == null ? '' : String(item.value)
    $('#redis-ttl').value = item?.ttl > 0 ? item.ttl : -1
    $('#redis-dialog').showModal()
  }

  async function saveRedis() {
    const key = $('#redis-key').value.trim()
    if (!key) return toast('Redis 键不能为空', true)
    const button = $('#save-redis')
    setBusy(button, true, '保存中…')
    try {
      await api('/redis/key/save', {
        method: 'POST',
        body: JSON.stringify({ key, value: $('#redis-value').value, ttl: Number($('#redis-ttl').value) }),
      })
      $('#redis-dialog').close()
      await loadRedis()
      toast('Redis 字符串键已保存')
    } catch (error) {
      toast('保存 Redis 键失败：' + error.message, true)
    } finally {
      setBusy(button, false)
    }
  }

  async function deleteRedis(key) {
    if (!window.confirm(`确认删除 Redis 键？\n${key}`)) return
    try {
      await api('/redis/key/delete', { method: 'POST', body: JSON.stringify({ key }) })
      await loadRedis()
      toast('Redis 键已删除')
    } catch (error) {
      toast('删除 Redis 键失败：' + error.message, true)
    }
  }

  function renderDedupe(data) {
    state.dedupe = data
    const duplicateCount = Number(data?.duplicatePictureCount || 0)
    $('#dedupe-summary').textContent = data
      ? `${data.duplicateGroupCount} 组 · 可移除 ${duplicateCount} 张`
      : '尚未扫描'
    $('#execute-dedup').disabled = duplicateCount === 0
    const metrics = [
      ['向量总数', data?.vectorCount ?? '—'],
      ['重复分组', data?.duplicateGroupCount ?? '—'],
      ['可移除图片', data?.duplicatePictureCount ?? '—'],
      ['已删除', data?.deletedCount ?? '—'],
    ]
    const grid = $('#dedupe-grid')
    grid.replaceChildren()
    metrics.forEach(([label, value]) => {
      const card = document.createElement('article')
      card.className = 'dedupe-metric'
      card.append(cell('span', label), cell('strong', String(value)))
      grid.append(card)
    })
    const body = $('#dedupe-table tbody')
    body.replaceChildren()
    const groups = data?.sampleGroups || []
    groups.forEach((group) => {
      const row = document.createElement('tr')
      row.append(
        cell('td', String(group.keepId)),
        cell('td', group.keepName || '未命名'),
        cell('td', group.spaceId == null ? '公共图库' : String(group.spaceId)),
        cell('td', (group.removeIds || []).join(', ')),
      )
      body.append(row)
    })
    const empty = $('#dedupe-empty')
    empty.textContent = data
      ? (groups.length ? '' : '没有发现重复向量')
      : '点击“扫描重复图片”生成预览，不会修改任何数据'
    empty.hidden = groups.length > 0
    const audit = $('#dedupe-audit')
    audit.hidden = !data?.auditFile
    audit.textContent = data?.auditFile ? `本地审计文件：${data.auditFile}` : ''
  }

  async function previewDuplicates() {
    const button = $('#preview-duplicates')
    setBusy(button, true, '正在扫描…')
    try {
      const data = await api('/pictures/duplicates/preview')
      renderDedupe(data)
      toast(data.duplicatePictureCount
        ? `发现 ${data.duplicateGroupCount} 组重复图片，可移除 ${data.duplicatePictureCount} 张`
        : '没有发现重复图片')
    } catch (error) {
      toast('扫描失败：' + error.message, true)
    } finally {
      setBusy(button, false)
    }
  }

  async function executeDedup() {
    const count = Number(state.dedupe?.duplicatePictureCount || 0)
    if (!count) return
    if (!window.confirm(`确认删除 ${count} 张重复图片？\n每组会保留创建时间最早的一张，并同步清理数据库、图片文件和本地向量。`)) return
    const button = $('#execute-dedup')
    setBusy(button, true, '正在清理…')
    try {
      const data = await api('/pictures/duplicates/execute', { method: 'POST' })
      renderDedupe(data)
      await loadTables()
      await previewDuplicates()
      const audit = $('#dedupe-audit')
      audit.hidden = !data.auditFile
      audit.textContent = data.auditFile ? `本地审计文件：${data.auditFile}` : ''
      if (data.failedCount) {
        toast(`已删除 ${data.deletedCount} 张，另有 ${data.failedCount} 张失败，请查看审计文件`, true)
      } else {
        toast(`重复图片清理完成，共删除 ${data.deletedCount} 张`)
      }
    } catch (error) {
      toast('清理失败：' + error.message, true)
    } finally {
      setBusy(button, false)
    }
  }

  async function refreshAll() {
    const button = $('#refresh-all')
    setBusy(button, true, '正在刷新…')
    const results = await Promise.allSettled([loadOverview(), loadTables(), loadRedis()])
    const failures = results.filter((result) => result.status === 'rejected')
    if (failures.length) {
      toast('部分数据加载失败：' + failures.map((item) => item.reason.message).join('；'), true)
    } else {
      toast('运行状态和数据已刷新')
    }
    setBusy(button, false)
  }

  function bindEvents() {
    $$('.nav-item').forEach((item) => item.addEventListener('click', () => setTab(item.dataset.tab)))
    $('#refresh-all').addEventListener('click', refreshAll)
    $('#settings-form').addEventListener('submit', saveSettings)
    $('#reload-tables').addEventListener('click', async () => {
      try { await loadTables(); toast('数据表清单已刷新') } catch (error) { toast(error.message, true) }
    })
    $('#table-select').addEventListener('change', async (event) => {
      state.selectedTable = event.target.value
      state.page = 1
      $('#insert-row').disabled = !state.selectedTable
      try { await loadRows() } catch (error) { toast(error.message, true) }
    })
    $('#insert-row').addEventListener('click', () => openRowDialog('insert'))
    $('#save-row').addEventListener('click', saveRow)
    $('#previous-page').addEventListener('click', async () => { state.page -= 1; await loadRows() })
    $('#next-page').addEventListener('click', async () => { state.page += 1; await loadRows() })
    $('#search-redis').addEventListener('click', async () => {
      try { await loadRedis() } catch (error) { toast(error.message, true) }
    })
    $('#redis-pattern').addEventListener('keydown', (event) => {
      if (event.key === 'Enter') { event.preventDefault(); $('#search-redis').click() }
    })
    $('#new-redis').addEventListener('click', () => openRedisDialog(null))
    $('#save-redis').addEventListener('click', saveRedis)
    $('#preview-duplicates').addEventListener('click', previewDuplicates)
    $('#execute-dedup').addEventListener('click', executeDedup)
  }

  bindEvents()
  renderDedupe(null)
  refreshAll()
})()
