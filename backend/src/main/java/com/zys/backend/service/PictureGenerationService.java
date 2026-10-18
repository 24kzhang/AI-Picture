package com.zys.backend.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.zys.backend.agent.service.AgentStorageService;
import com.zys.backend.agent.service.StoredObject;
import com.zys.backend.api.aliyunai.QwenImageGenerator;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.manager.auth.SpaceUserAuthManager;
import com.zys.backend.manager.auth.model.SpaceUserPermissionConstant;
import com.zys.backend.model.dto.picture.PictureUploadRequest;
import com.zys.backend.model.entity.Space;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.vo.PictureVO;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;

/** 新增图片的独立生图任务；仅显式保存时才计入图库。 */
@Service
public class PictureGenerationService implements ApplicationRunner {
    @Resource private JdbcTemplate jdbc;
    @Resource private TransactionTemplate transaction;
    @Resource private QwenImageGenerator generator;
    @Resource private AgentStorageService storage;
    @Resource private SpaceService spaces;
    @Resource private SpaceUserAuthManager permissions;
    @Resource private UserService users;
    @Resource private PictureService pictures;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"gallery-image-generation");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final String workerId = UUID.randomUUID().toString();
    private static final int LEASE_MINUTES = 30;
    @Override public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS picture_generation_task (id VARCHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,space_id BIGINT NULL,prompt TEXT NOT NULL,width INT NOT NULL,height INT NOT NULL,status VARCHAR(24) NOT NULL,request_key VARCHAR(36) NOT NULL,digest VARCHAR(64) NOT NULL,model_id VARCHAR(100) NULL,source_url VARCHAR(1024) NULL,result_url VARCHAR(1024) NULL,error_message VARCHAR(300) NULL,saved_picture_id BIGINT NULL,worker_id VARCHAR(64) NULL,lease_epoch BIGINT NOT NULL DEFAULT 0,lease_until TIMESTAMP NULL,create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,UNIQUE KEY user_request(user_id,request_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        addColumnIfMissing("worker_id VARCHAR(64) NULL");
        addColumnIfMissing("lease_epoch BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing("lease_until TIMESTAMP NULL");
        jdbc.update("UPDATE picture_generation_task SET status='UNKNOWN',error_message='服务中断，生成结果未知；请明确重新生成，不会自动重复推理',worker_id=NULL,lease_until=NULL,lease_epoch=lease_epoch+1 WHERE status='RUNNING' AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE picture_generation_task SET status='OUTPUT_PENDING',error_message='生图完成，转存被中断，可重试转存',worker_id=NULL,lease_until=NULL,lease_epoch=lease_epoch+1 WHERE status='TRANSFER' AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)");
        for(Map<String,Object> row:jdbc.queryForList("SELECT id FROM picture_generation_task WHERE status='QUEUED' ORDER BY create_time")) dispatch(String.valueOf(row.get("id")),false);
    }
    private void addColumnIfMissing(String definition) { try { jdbc.execute("ALTER TABLE picture_generation_task ADD COLUMN " + definition); } catch (Exception ignored) { } }
    private BusinessException fail(String text){return new BusinessException(ErrorCode.OPERATION_ERROR,text);}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private Space requireUpload(Long spaceId,User user) {
        if(user==null)throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        if(spaceId==null)return null;
        Space space=spaces.getById(spaceId);if(space==null)throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"空间不存在");
        if(!users.isAdmin(user)&&!permissions.getPermissionList(space,user).contains(SpaceUserPermissionConstant.PICTURE_UPLOAD))throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"没有目标空间的图片上传权限");
        return space;
    }
    private void capacity(Space space,long bytes) {
        if(space!=null&&(space.getTotalCount()>=space.getMaxCount()||space.getTotalSize()+bytes>space.getMaxSize()))throw fail("目标空间容量不足，请清理或选择其他空间");
    }
    private Map<String,Object> row(String id,boolean lock) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM picture_generation_task WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"生图任务不存在");return rows.get(0);
    }
    private Map<String,Object> owned(String id,User user) {
        Map<String,Object> row=row(id,false);if(!Objects.equals(number(row.get("user_id")),user.getId()))throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"无权访问该生图任务");
        requireUpload(number(row.get("space_id")),user);return row;
    }
    public Map<String,Object> snapshot(String id,User user) {return view(owned(id,user));}
    public List<Map<String,Object>> recent(Long spaceId,User user) {
        requireUpload(spaceId,user);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> task:jdbc.queryForList("SELECT * FROM picture_generation_task WHERE user_id=? AND space_id <=> ? ORDER BY create_time DESC LIMIT 20",user.getId(),spaceId))result.add(view(task));
        return result;
    }
    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",row.get("id"));result.put("status",row.get("status"));result.put("prompt",row.get("prompt"));result.put("width",row.get("width"));result.put("height",row.get("height"));
        result.put("spaceId",row.get("space_id")==null?null:String.valueOf(row.get("space_id")));result.put("resultUrl",row.get("result_url"));result.put("errorMessage",row.get("error_message"));result.put("savedPictureId",row.get("saved_picture_id")==null?null:String.valueOf(row.get("saved_picture_id")));return result;
    }
    public Map<String,Object> create(User user,Long spaceId,String prompt,Integer width,Integer height,String key) {
        Space space=requireUpload(spaceId,user);
        if(StrUtil.isBlank(prompt)||prompt.trim().length()>4000)throw new BusinessException(ErrorCode.PARAMS_ERROR,"图片描述需要 1 到 4000 个字符");
        int w=width==null?1024:width,h=height==null?1024:height;
        if(w<256||w>2048||h<256||h>2048)throw new BusinessException(ErrorCode.PARAMS_ERROR,"图片宽高需要在 256 到 2048 之间");
        try{UUID.fromString(key);}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR,"缺少有效幂等键");}
        String digest=DigestUtil.sha256Hex(spaceId+":"+prompt.trim()+":"+w+":"+h);
        List<Map<String,Object>> existing=jdbc.queryForList("SELECT * FROM picture_generation_task WHERE user_id=? AND request_key=?",user.getId(),key);
        if(!existing.isEmpty())return replay(existing.get(0),digest);
        capacity(space,0);
        String id=UUID.randomUUID().toString();
        try {jdbc.update("INSERT INTO picture_generation_task(id,user_id,space_id,prompt,width,height,status,request_key,digest) VALUES (?,?,?,?,?,?,'QUEUED',?,?)",id,user.getId(),spaceId,prompt.trim(),w,h,key,digest);}
        catch(DuplicateKeyException race){return replay(jdbc.queryForList("SELECT * FROM picture_generation_task WHERE user_id=? AND request_key=?",user.getId(),key).get(0),digest);}
        dispatch(id,false);return snapshot(id,user);
    }
    private Map<String,Object> replay(Map<String,Object> row,String digest){if(!Objects.equals(row.get("digest"),digest))throw fail("幂等键已用于其他生图请求");return view(row);}
    private void dispatch(String id,boolean transfer) {
        try{executor.execute(()->execute(id,transfer));}catch(RejectedExecutionException e){jdbc.update("UPDATE picture_generation_task SET status=?,error_message='生图队列已满，请稍后重试' WHERE id=?",transfer?"OUTPUT_PENDING":"FAILED",id);}
    }
    private boolean claim(String id, boolean transfer) {
        String from = transfer ? "OUTPUT_PENDING" : "QUEUED";
        String to = transfer ? "TRANSFER" : "RUNNING";
        return jdbc.update("UPDATE picture_generation_task SET status=?,worker_id=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL " + LEASE_MINUTES + " MINUTE),lease_epoch=lease_epoch+1 WHERE id=? AND status=? AND (worker_id IS NULL OR lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)",to,workerId,id,from)==1;
    }
    private void execute(String id,boolean transfer) {
        if (!claim(id, transfer)) return;
        Map<String,Object> claimed = row(id,false);
        Number epoch = (Number) claimed.get("lease_epoch");
        boolean hasReceipt=transfer;
        try {
            Map<String,Object> task=claimed;User user=users.getById(number(task.get("user_id")));requireUpload(number(task.get("space_id")),user);
            if(!transfer){
                QwenImageGenerator.Receipt receipt=generator.generate(String.valueOf(task.get("prompt")),((Number)task.get("width")).intValue(),((Number)task.get("height")).intValue());
                if(jdbc.update("UPDATE picture_generation_task SET status='TRANSFER',model_id=?,source_url=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL " + LEASE_MINUTES + " MINUTE) WHERE id=? AND worker_id=? AND lease_epoch=?",receipt.getId(),receipt.getUrl(),id,workerId,epoch)==0)return;
                hasReceipt=true;task=row(id,false);epoch=(Number)task.get("lease_epoch");
            }
            byte[] bytes=generator.download(String.valueOf(task.get("source_url")));
            StoredObject output=storage.storeSessionAsset(number(task.get("user_id")),"generated", "png",bytes);
            jdbc.update("UPDATE picture_generation_task SET status='SUCCEEDED',result_url=?,width=?,height=?,error_message=NULL,worker_id=NULL,lease_until=NULL WHERE id=? AND worker_id=? AND lease_epoch=?",output.getUrl(),output.getWidth(),output.getHeight(),id,workerId,epoch);
        } catch(Exception e) {
            boolean known=e instanceof BusinessException;
            String status=hasReceipt?"OUTPUT_PENDING":known?"FAILED":"UNKNOWN";
            String error=hasReceipt?"图片已生成，但结果转存失败；可重试转存":known?e.getMessage():"模型连接中断或超时，结果未知；不会自动重复生成";
            jdbc.update("UPDATE picture_generation_task SET status=?,error_message=?,worker_id=NULL,lease_until=NULL WHERE id=? AND worker_id=? AND lease_epoch=?",status,error.length()>300?error.substring(0,300):error,id,workerId,epoch);
        }
    }
    public Map<String,Object> retryTransfer(String id,User user) {
        owned(id,user);
        if(jdbc.update("UPDATE picture_generation_task SET status='OUTPUT_PENDING',error_message=NULL,worker_id=NULL,lease_until=NULL WHERE id=? AND status='OUTPUT_PENDING' AND (worker_id IS NULL OR lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)",id)==1)dispatch(id,true);
        return snapshot(id,user);
    }
    public PictureVO save(String id,String name,User user) {
        owned(id,user);
        if(name!=null&&name.length()>100)throw new BusinessException(ErrorCode.PARAMS_ERROR,"图片名称不能超过 100 字符");
        return transaction.execute(status->{
            Map<String,Object> task=row(id,true);Long spaceId=number(task.get("space_id"));
            if(spaceId!=null)jdbc.queryForList("SELECT id FROM space WHERE id=? FOR UPDATE",spaceId);
            Space space=requireUpload(spaceId,user);
            Long saved=number(task.get("saved_picture_id"));
            if(saved!=null){com.zys.backend.model.entity.Picture picture=pictures.getById(saved);if(picture==null)throw fail("已保存的图片被删除，请重新生成");return PictureVO.objToVo(picture);}
            if(!"SUCCEEDED".equals(task.get("status")))throw fail("图片尚未生成完成，不能插入图库");
            String resultUrl=String.valueOf(task.get("result_url"));capacity(space,storage.load(resultUrl).length);
            PictureUploadRequest request=new PictureUploadRequest();request.setSpaceId(spaceId);request.setPicName(StrUtil.isBlank(name)?"AI 生成图片":name.trim());
            PictureVO picture=pictures.uploadPicture(resultUrl,request,user);
            jdbc.update("UPDATE picture_generation_task SET saved_picture_id=? WHERE id=?",picture.getId(),id);return picture;
        });
    }
    @PreDestroy public void stop(){executor.shutdownNow();}
}
