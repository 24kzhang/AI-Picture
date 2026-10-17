package com.zys.backend.service;

import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

/** All callers must hold a transaction; database locks also work across instances. */
@Component
@org.springframework.core.annotation.Order(-100)
public class MutationGuard implements org.springframework.boot.ApplicationRunner {
    @Resource private JdbcTemplate jdbc;
    @Override public void run(org.springframework.boot.ApplicationArguments args) {
        for (String table : new String[]{"user", "space", "space_user"}) {
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='mutationVersion'",Integer.class,table);
            if(count==null||count==0) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN mutationVersion BIGINT NOT NULL DEFAULT 0");
        }
    }
    public void requireEmptySpace(Long id) {
        if (!jdbc.queryForList("SELECT id FROM picture WHERE spaceId=? AND isDelete=0 LIMIT 1",id).isEmpty())
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"请先移除空间内的图片再删除空间");
        Integer installed=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='video_project'",Integer.class);
        if(installed!=null&&installed>0&&!jdbc.queryForList("SELECT id FROM video_project WHERE space_id=? LIMIT 1",id).isEmpty())
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"空间内仍有视频工程，无法删除");
    }
    public void lockUser(Long id) { jdbc.queryForList("SELECT id FROM user WHERE id=? AND isDelete=0 FOR UPDATE", id); }
    public void lockSpace(Long id) {
        if (jdbc.queryForList("SELECT id FROM space WHERE id=? AND isDelete=0 FOR UPDATE", id).isEmpty())
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "空间不存在");
    }
    public void requireVersion(Long expected) {
        if (expected == null || expected < 0) throw new BusinessException(ErrorCode.PARAMS_ERROR, "缺少有效版本，请刷新后重试");
    }
    public void conflict(boolean updated) {
        if (!updated) throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片已被其他成员更新或删除；本次修改未保存，请刷新后重新确认");
    }
}
