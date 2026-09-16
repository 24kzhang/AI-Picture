package com.zys.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zys.backend.model.entity.IntegrationOutbox;
import org.apache.ibatis.annotations.Mapper;

/**
 * 集成事务消息 Outbox 数据库操作
 */
@Mapper
public interface IntegrationOutboxMapper extends BaseMapper<IntegrationOutbox> {
}
