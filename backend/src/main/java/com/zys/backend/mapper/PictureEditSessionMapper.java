package com.zys.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zys.backend.model.entity.PictureEditSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 图片编辑会话数据库操作
 */
@Mapper
public interface PictureEditSessionMapper extends BaseMapper<PictureEditSession> {
}
