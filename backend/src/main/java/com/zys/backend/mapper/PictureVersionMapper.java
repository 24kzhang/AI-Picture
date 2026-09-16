package com.zys.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zys.backend.model.entity.PictureVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 图片版本数据库操作
 */
@Mapper
public interface PictureVersionMapper extends BaseMapper<PictureVersion> {
}
