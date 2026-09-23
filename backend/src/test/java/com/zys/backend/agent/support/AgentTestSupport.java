package com.zys.backend.agent.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 单测支持：脱离 Spring/MyBatis 环境时手工初始化实体的 TableInfo，
 * 使 Wrappers.lambdaQuery/lambdaUpdate 的列解析可用。
 */
public final class AgentTestSupport {

    private AgentTestSupport() {
    }

    public static void initTableInfo(Class<?>... entities) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("test");
        for (Class<?> entity : entities) {
            if (TableInfoHelper.getTableInfo(entity) == null) {
                TableInfoHelper.initTableInfo(assistant, entity);
            }
        }
    }
}
