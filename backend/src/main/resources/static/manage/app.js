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
    rowEditorMode: 'form',
    editingPrimaryKey: {},
    dedupe: null,
  }

  const INTEGER_TYPES = ['TINYINT', 'SMALLINT', 'MEDIUMINT', 'INT', 'INTEGER', 'BIGINT', 'YEAR']
  const DECIMAL_TYPES = ['DECIMAL', 'NUMERIC', 'FLOAT', 'DOUBLE', 'REAL']
  const LONG_TEXT_TYPES = ['TEXT', 'TINYTEXT', 'MEDIUMTEXT', 'LONGTEXT', 'JSON']
  const BINARY_TYPES = ['BINARY', 'VARBINARY', 'BLOB', 'TINYBLOB', 'MEDIUMBLOB', 'LONGBLOB']
  const DATETIME_TYPES = ['DATETIME', 'TIMESTAMP']

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

  function columnCategory(column) {
    const type = String(column.type || '').toUpperCase()
    if (INTEGER_TYPES.includes(type)) return 'integer'
    if (DECIMAL_TYPES.includes(type)) return 'decimal'
    if (DATETIME_TYPES.includes(type)) return 'datetime'
    if (type === 'DATE') return 'date'
    if (type === 'TIME') return 'time'
    if (type === 'BOOLEAN' || type === 'BOOL' || type === 'BIT') return 'boolean'
    if (LONG_TEXT_TYPES.includes(type)) return 'longtext'
    if (BINARY_TYPES.includes(type)) return 'binary'
    return 'text'
  }

  function columnHasDefault(column) {
    return column.defaultValue !== null && column.defaultValue !== undefined && column.defaultValue !== ''
  }

  function inputValueFor(column, value) {
    if (value === null || value === undefined) return ''
    const text = String(value)
    const category = columnCategory(column)
    if (category === 'datetime') {
      const match = text.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}(?::\d{2})?)/)
      return match ? `${match[1]}T${match[2]}` : text
    }
    if (category === 'date') {
      const match = text.match(/\d{4}-\d{2}-\d{2}/)
      return match ? match[0] : text
    }
    if (category === 'time') {
      const match = text.match(/\d{1,2}:\d{2}(?::\d{2})?/)
      return match ? match[0] : text
    }
    if (category === 'boolean') {
      if (text === 'true' || text === '1') return '1'
      if (text === 'false' || text === '0') return '0'
    }
    if (category === 'binary') {
      return text.replace(/^\[二进制 \d+ 字节\]\s*/, '')
    }
    return text
  }

  function createControl(column, category, value) {
    let control
    if (category === 'longtext' || category === 'binary') {
      control = document.createElement('textarea')
      control.rows = category === 'binary' ? 2 : 3
      if (category === 'binary') {
        control.title = '二进制字段只读展示，需要修改请切换到 JSON 高级模式'
      }
    } else if (category === 'boolean') {
      control = document.createElement('select')
      control.add(new Option('true', '1'))
      control.add(new Option('false', '0'))
    } else {
      control = document.createElement('input')
      if (category === 'date') control.type = 'date'
      else if (category === 'time') { control.type = 'time'; control.step = '1' }
      else if (category === 'datetime') { control.type = 'datetime-local'; control.step = '1' }
      else {
        control.type = 'text'
        if (category === 'integer') control.inputMode = 'numeric'
        else if (category === 'decimal') control.inputMode = 'decimal'
        else if (column.size && Number(column.size) > 0) control.maxLength = Number(column.size)
      }
    }
    control.value = value
    return control
  }

  function updateControlDisabled(field, column) {
    const control = $('[data-control]', field)
    const nullBox = $('input[data-null]', field)
    const lockedByMode = state.rowMode === 'edit' && column.primaryKey
    const lockedByAuto = column.autoIncrement && state.rowMode === 'insert'
    const lockedByType = columnCategory(column) === 'binary'
    const lockedByNull = Boolean(nullBox && nullBox.checked)
    control.disabled = lockedByMode || lockedByAuto || lockedByType || lockedByNull
  }

  function renderRowForm(mode, row) {
    const container = $('#row-fields')
    container.replaceChildren()
    $('#form-tip').textContent = mode === 'insert'
      ? '按字段填写即可；程序会自动校验类型、必填项与长度。留空且有默认值的字段将使用数据库默认值。'
      : '修改需要更新的字段即可；程序会自动校验类型、必填项与长度。主键只读。'
    state.columns.forEach((column) => {
      const category = columnCategory(column)
      const value = row[column.name]
      const field = document.createElement('div')
      field.className = 'row-field'
      field.dataset.column = column.name

      const head = document.createElement('div')
      head.className = 'row-field-head'
      const label = document.createElement('label')
      label.className = 'row-field-label'
      const typeText = String(column.type || '') + (column.size ? `(${column.size})` : '')
      const traits = [typeText]
      if (column.primaryKey) traits.push('主键')
      if (column.autoIncrement) traits.push('自增')
      if (!column.nullable) traits.push('非空')
      if (columnHasDefault(column)) traits.push('默认 ' + column.defaultValue)
      label.append(cell('span', column.name, 'row-field-name'), cell('span', traits.join(' · '), 'row-field-type'))
      head.append(label)

      if (column.nullable) {
        const nullToggle = document.createElement('label')
        nullToggle.className = 'null-toggle'
        const box = document.createElement('input')
        box.type = 'checkbox'
        box.dataset.null = 'true'
        box.checked = mode === 'insert'
          ? !columnHasDefault(column)
          : (value === null || value === undefined)
        nullToggle.append(box, document.createTextNode(' NULL'))
        head.append(nullToggle)
      }
      field.append(head)

      const controlWrap = document.createElement('div')
      controlWrap.className = 'row-field-control'
      const control = createControl(column, category, inputValueFor(column, value))
      control.dataset.control = 'true'
      controlWrap.append(control)
      field.append(controlWrap)

      const error = cell('p', '', 'row-field-error')
      error.dataset.error = 'true'
      error.hidden = true
      field.append(error)

      const nullBox = $('input[data-null]', field)
      if (nullBox) nullBox.addEventListener('change', () => updateControlDisabled(field, column))
      updateControlDisabled(field, column)
      container.append(field)
    })
    state.rowEditorMode = 'form'
    $$('.mode-button').forEach((button) => button.classList.toggle('active', button.dataset.mode === 'form'))
    $('#form-tip').hidden = false
    $('#row-fields').hidden = false
    $('#row-json-wrap').hidden = true
  }

  function openRowDialog(mode, row = {}) {
    state.rowMode = mode
    state.editingPrimaryKey = Object.fromEntries(state.primaryKeys.map((key) => [key, row[key]]))
    $('#row-dialog-title').textContent = mode === 'insert' ? `新增 ${state.selectedTable} 记录` : `编辑 ${state.selectedTable} 记录`
    renderRowForm(mode, row)
    $('#row-dialog').showModal()
  }

  function snapshotForm() {
    const values = {}
    $$('.row-field', $('#row-fields')).forEach((field) => {
      const column = state.columns.find((item) => item.name === field.dataset.column)
      if (!column) return
      const control = $('[data-control]', field)
      const nullBox = $('input[data-null]', field)
      if (nullBox && nullBox.checked) {
        values[column.name] = null
        return
      }
      const raw = control.value
      if (column.autoIncrement && state.rowMode === 'insert' && raw === '') return
      values[column.name] = raw === '' ? null : raw
    })
    return values
  }

  function applyValues(values) {
    $$('.row-field', $('#row-fields')).forEach((field) => {
      const column = state.columns.find((item) => item.name === field.dataset.column)
      if (!column) return
      const control = $('[data-control]', field)
      const nullBox = $('input[data-null]', field)
      const has = Object.prototype.hasOwnProperty.call(values, column.name)
      const value = has ? values[column.name] : undefined
      const isNull = has && value === null
      control.value = inputValueFor(column, isNull ? null : value)
      if (nullBox) nullBox.checked = isNull
      updateControlDisabled(field, column)
    })
  }

  function switchEditorMode(mode) {
    if (mode === state.rowEditorMode) return
    if (mode === 'json') {
      $('#row-json').value = JSON.stringify(snapshotForm(), null, 2)
      $('#form-tip').hidden = true
      $('#row-fields').hidden = true
      $('#row-json-wrap').hidden = false
    } else {
      let parsed
      try {
        parsed = JSON.parse($('#row-json').value)
        if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error('必须是 JSON 对象')
      } catch (error) {
        toast('JSON 格式错误，无法切换回表单：' + error.message, true)
        return
      }
      applyValues(parsed)
      $('#form-tip').hidden = false
      $('#row-fields').hidden = false
      $('#row-json-wrap').hidden = true
    }
    state.rowEditorMode = mode
    $$('.mode-button').forEach((button) => button.classList.toggle('active', button.dataset.mode === mode))
  }

  function reportFieldError(field, message, firstError) {
    const error = $('[data-error]', field)
    error.textContent = message
    error.hidden = false
    field.classList.add('has-error')
    if (!firstError) {
      field.scrollIntoView({ block: 'nearest' })
      return message
    }
    return firstError
  }

  function parseFieldValue(column, category, raw) {
    const text = raw.trim()
    if (category === 'binary') return { error: `${column.name} 为二进制字段，请使用 JSON 高级模式` }
    if (category === 'integer') {
      return /^-?\d+$/.test(text) ? { value: text } : { error: `${column.name} 必须是整数` }
    }
    if (category === 'decimal') {
      return /^-?\d+(\.\d+)?$/.test(text) ? { value: text } : { error: `${column.name} 必须是数字` }
    }
    if (category === 'boolean') {
      return { value: text === '1' || text.toLowerCase() === 'true' ? '1' : '0' }
    }
    if (category === 'date') {
      return /^\d{4}-\d{2}-\d{2}$/.test(text) ? { value: text } : { error: `${column.name} 日期格式应为 YYYY-MM-DD` }
    }
    if (category === 'time') {
      return /^\d{1,2}:\d{2}(:\d{2})?$/.test(text) ? { value: text } : { error: `${column.name} 时间格式应为 HH:MM:SS` }
    }
    if (category === 'datetime') {
      const normalized = text.replace(' ', 'T')
      return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(normalized)
        ? { value: normalized.replace('T', ' ') }
        : { error: `${column.name} 时间格式应为 YYYY-MM-DD HH:MM:SS` }
    }
    if (column.size && Number(column.size) > 0 && Number(column.size) <= 65535 && raw.length > Number(column.size)) {
      return { error: `${column.name} 长度不能超过 ${column.size}` }
    }
    return { value: raw }
  }

  function collectFormValues() {
    const values = {}
    let firstError = null
    $$('.row-field', $('#row-fields')).forEach((field) => {
      const column = state.columns.find((item) => item.name === field.dataset.column)
      const control = $('[data-control]', field)
      const errorEl = $('[data-error]', field)
      errorEl.hidden = true
      errorEl.textContent = ''
      field.classList.remove('has-error')
      if (!column) return
      const category = columnCategory(column)
      const nullBox = $('input[data-null]', field)
      const isNull = Boolean(nullBox && nullBox.checked)
      const raw = control.value

      if (isNull) {
        values[column.name] = null
        return
      }
      if (category === 'binary') return
      if (column.autoIncrement && state.rowMode === 'insert' && raw === '') return
      if (raw === '') {
        if (state.rowMode === 'insert') {
          if (columnHasDefault(column)) return
          if (column.nullable) return
          firstError = reportFieldError(field, `${column.name} 为必填字段`, firstError)
          return
        }
        if (category === 'text' || category === 'longtext') {
          values[column.name] = ''
          return
        }
        if (!column.nullable) {
          firstError = reportFieldError(field, `${column.name} 为必填字段`, firstError)
        } else {
          firstError = reportFieldError(field, `${column.name} 请填写内容或勾选 NULL`, firstError)
        }
        return
      }
      const parsed = parseFieldValue(column, category, raw)
      if (parsed.error) {
        firstError = reportFieldError(field, parsed.error, firstError)
        return
      }
      values[column.name] = parsed.value
    })
    if (firstError) throw new Error(firstError)
    return values
  }

  async function saveRow() {
    let values
    if (state.rowEditorMode === 'form') {
      try {
        values = collectFormValues()
      } catch (error) {
        toast('请检查表单：' + error.message, true)
        return
      }
    } else {
      try {
        values = JSON.parse($('#row-json').value)
        if (!values || Array.isArray(values) || typeof values !== 'object') throw new Error('必须是 JSON 对象')
      } catch (error) {
        toast('JSON 格式错误：' + error.message, true)
        return
      }
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
    $$('.mode-button').forEach((button) => button.addEventListener('click', () => switchEditorMode(button.dataset.mode)))
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
