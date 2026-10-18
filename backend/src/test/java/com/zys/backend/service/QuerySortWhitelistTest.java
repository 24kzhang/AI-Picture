package com.zys.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.model.dto.space.SpaceQueryRequest;
import com.zys.backend.model.dto.user.UserQueryRequest;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.service.impl.SpaceServiceImpl;
import com.zys.backend.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuerySortWhitelistTest {

    @Test
    void spaceQueryOnlyAcceptsKnownSortColumns() {
        SpaceServiceImpl service = new SpaceServiceImpl();
        SpaceQueryRequest request = new SpaceQueryRequest();
        request.setSortField("createTime");
        request.setSortOrder("ascend");
        QueryWrapper<Space> wrapper = assertDoesNotThrow(() -> service.getQueryWrapper(request));
        assertTrue(wrapper.getSqlSegment().contains("createTime"));

        request.setSortField("id desc, (select sleep(1))");
        assertThrows(BusinessException.class, () -> service.getQueryWrapper(request));
    }

    @Test
    void userQueryOnlyAcceptsKnownSortColumnsAndAllowsMissingDirection() {
        UserServiceImpl service = new UserServiceImpl();
        UserQueryRequest request = new UserQueryRequest();
        request.setSortField("id");
        request.setSortOrder(null);
        assertDoesNotThrow(() -> service.getQueryWrapper(request));

        request.setSortField("userName, id");
        assertThrows(BusinessException.class, () -> service.getQueryWrapper(request));
    }
}
