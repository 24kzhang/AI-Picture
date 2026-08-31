/**
 * 图片审核状态
 * @author 10415
 */
export const PIC_REVIEW_STATUS_ENUM = {
  REVIEWING: 0,
  PASS: 1,
  REJECT: 2,
}

/**
 * 图片审核状态文案
 */
export const PIC_REVIEW_STATUS_MAP = {
  0: '待审核',
  1: '通过',
  2: '拒绝',
}

/**
 * 图片审核下拉表单选项
 */
export const PIC_REVIEW_STATUS_OPTIONS = Object.keys(PIC_REVIEW_STATUS_MAP).map((key) => {
  return {
    label: PIC_REVIEW_STATUS_MAP[key],
    value: key,
  }
})

/**
 * 图片编辑消息类型枚举
 */
export const PICTURE_EDIT_MESSAGE_TYPE_ENUM = {
  INFO: 'INFO',
  ERROR: 'ERROR',
  ENTER_EDIT: 'ENTER_EDIT',
  EXIT_EDIT: 'EXIT_EDIT',
  EDIT_ACTION: 'EDIT_ACTION',
};

/**
 * 图片编辑消息类型映射
 */
export const PICTURE_EDIT_MESSAGE_TYPE_MAP = {
  INFO: '发送通知',
  ERROR: '发送错误',
  ENTER_EDIT: '进入编辑状态',
  EXIT_EDIT: '退出编辑状态',
  EDIT_ACTION: '执行编辑操作',
};

/**
 * 图片编辑操作枚举
 */
export const PICTURE_EDIT_ACTION_ENUM = {
  ZOOM_IN: 'ZOOM_IN',
  ZOOM_OUT: 'ZOOM_OUT',
  ROTATE_LEFT: 'ROTATE_LEFT',
  ROTATE_RIGHT: 'ROTATE_RIGHT',
  FLIP_HORIZONTAL: 'FLIP_HORIZONTAL',
  FLIP_VERTICAL: 'FLIP_VERTICAL',
  MOVE_STICKER: 'MOVE_STICKER',
  BRUSH_STROKE: 'BRUSH_STROKE',
  ADD_TEXT: 'ADD_TEXT',
  ADD_STICKER: 'ADD_STICKER',
  SET_FILTER: 'SET_FILTER',
  UNDO: 'UNDO',
  REDO: 'REDO',
  RESET: 'RESET',
  AI_EDIT: 'AI_EDIT',
  SYNC_STATE: 'SYNC_STATE',
};

/**
 * 图片编辑操作映射
 */
export const PICTURE_EDIT_ACTION_MAP = {
  ZOOM_IN: '放大操作',
  ZOOM_OUT: '缩小操作',
  ROTATE_LEFT: '左旋操作',
  ROTATE_RIGHT: '右旋操作',
  FLIP_HORIZONTAL: '水平翻转',
  FLIP_VERTICAL: '垂直翻转',
  MOVE_STICKER: '移动贴图',
  BRUSH_STROKE: '画笔绘制',
  ADD_TEXT: '添加文字',
  ADD_STICKER: '添加贴图',
  SET_FILTER: '调整滤镜',
  UNDO: '撤销操作',
  REDO: '重做操作',
  RESET: '重置图片',
  AI_EDIT: '应用 AI 编辑结果',
  SYNC_STATE: '同步编辑状态',
};
