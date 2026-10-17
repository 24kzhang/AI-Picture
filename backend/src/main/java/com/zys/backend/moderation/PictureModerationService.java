package com.zys.backend.moderation;

import com.zys.backend.manager.CosStorageManager;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.mapper.PictureMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class PictureModerationService {
    @Resource private PictureMapper pictures;
    @Resource private JdbcTemplate jdbc;
    @Resource private CosStorageManager storage;
    @Resource private QwenModerationClient model;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"picture-ai-review");t.setDaemon(true);return t;});

    @Scheduled(fixedDelay=5000,initialDelay=15000)
    public void scan(){
        if(!running.compareAndSet(false,true))return;
        worker.submit(()->{try{
            List<Picture> waiting=pictures.selectList(new QueryWrapper<Picture>().eq("reviewStatus",0).eq("reviewerId",0)
                    .and(q->q.eq("reviewMessage","等待AI审核").or().lt("reviewTime",new java.util.Date(System.currentTimeMillis()-300000)))
                    .orderByAsc("reviewTime").last("LIMIT 1"));
            if(!waiting.isEmpty())review(waiting.get(0));
        }catch(Exception e){log.warn("图片审核暂不可用：{}",e.getMessage());}finally{running.set(false);}});
    }

    public void review(Picture snapshot){
        int status=0;String message;
        try(CosStorageManager.ManagedImageFile file=storage.materialize(snapshot.getUrl())){
            QwenModerationClient.Result result=model.review(file.getPath());status=result.status;message=result.message;
        }catch(Exception e){message="AI审核暂不可用，等待重试或管理员审核";log.warn("图片AI审核失败，pictureId={}：{}",snapshot.getId(),e.getMessage());}
        // A manual decision or a newer upload always takes precedence over this snapshot.
        jdbc.update("UPDATE picture SET reviewStatus=?,reviewMessage=?,reviewTime=NOW() WHERE id=? AND isDelete=0 AND reviewStatus=0 AND reviewerId=0 AND url=? AND reviewTime<=>? AND updateTime<=>?",
                status,message,snapshot.getId(),snapshot.getUrl(),snapshot.getReviewTime(),snapshot.getUpdateTime());
    }
    @PreDestroy public void stop(){worker.shutdownNow();}
}
