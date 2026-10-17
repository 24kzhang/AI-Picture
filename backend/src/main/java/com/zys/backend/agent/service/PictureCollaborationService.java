package com.zys.backend.agent.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zys.backend.agent.model.PictureCollaborationChanged;
import com.zys.backend.agent.model.vo.AgentSessionVO;
import com.zys.backend.constant.AgentConstant;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.model.entity.*;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import com.zys.backend.model.enums.SpaceTypeEnum;
import com.zys.backend.service.SpaceService;
import com.zys.backend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.util.*;

/** Shared image drafts use a database row lock and a fenced browser lease. */
@Service
@Slf4j
public class PictureCollaborationService implements ApplicationRunner {
    @Resource private JdbcTemplate jdbc;
    @Resource private TransactionTemplate transaction;
    @Resource @Lazy private AgentSessionService sessions;
    @Resource private AgentAuthService auth;
    @Resource private EditLeaseService leases;
    @Resource private AgentAssetService assets;
    @Resource private LayerDocumentService documents;
    @Resource private SpaceService spaces;
    @Resource private UserService users;
    @Resource private ApplicationEventPublisher publisher;
    private static final long TTL = 60_000;

    @Override public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS picture_annotation_member (picture_id BIGINT NOT NULL,client_id VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,session_id BIGINT NOT NULL,epoch BIGINT NOT NULL,lease_until BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(picture_id,client_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS picture_annotation_document (session_id BIGINT PRIMARY KEY,applied_revision INT NOT NULL,document MEDIUMTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS picture_collaboration (picture_id BIGINT PRIMARY KEY,session_id BIGINT NULL,editor_id BIGINT NULL,client_id VARCHAR(64) NULL,epoch BIGINT NOT NULL DEFAULT 0,lease_until BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS picture_collaboration_run (run_id BIGINT PRIMARY KEY,session_id BIGINT NOT NULL,user_id BIGINT NOT NULL,client_id VARCHAR(64) NOT NULL,epoch BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS picture_collaboration_request (session_id BIGINT NOT NULL,request_key VARCHAR(100) NOT NULL,digest VARCHAR(64) NOT NULL,PRIMARY KEY(session_id,request_key)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE IF NOT EXISTS picture_collaboration_session (session_id BIGINT PRIMARY KEY,picture_id BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.update("INSERT IGNORE INTO picture_collaboration_session SELECT session_id,picture_id FROM picture_collaboration WHERE session_id IS NOT NULL");
    }

    public boolean shared(Long sessionId) {
        return sessionId != null && !jdbc.queryForList("SELECT picture_id FROM picture_collaboration_session WHERE session_id=?",sessionId).isEmpty();
    }
    private Map<String,Object> row(Long pictureId,boolean lock) {
        List<Map<String,Object>> records=jdbc.queryForList("SELECT * FROM picture_collaboration WHERE picture_id=?"+(lock?" FOR UPDATE":""),pictureId);
        if(records.isEmpty())throw failure("协同工程尚未创建");
        return records.get(0);
    }
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    private static BusinessException failure(String reason){return new BusinessException(ErrorCode.OPERATION_ERROR,reason);}
    public boolean isTeam(Picture picture) {
        Space space=picture.getSpaceId()==null?null:spaces.getById(picture.getSpaceId());
        return space!=null&&Objects.equals(space.getSpaceType(),SpaceTypeEnum.TEAM.getValue());
    }
    public boolean hasSharedPicture(Long pictureId) {
        return !jdbc.queryForList("SELECT picture_id FROM picture_collaboration WHERE picture_id=?",pictureId).isEmpty();
    }
    private Picture picture(Long id,User user,boolean edit) {
        Picture picture=sessions.requirePicture(id);
        if(edit)auth.requireEdit(user,picture);else auth.requireView(user,picture);
        Space space=picture.getSpaceId()==null?null:spaces.getById(picture.getSpaceId());
        if(space==null||!Objects.equals(space.getSpaceType(),SpaceTypeEnum.TEAM.getValue()))throw failure("协同工程仅用于团队空间图片");
        return picture;
    }
    public AgentSessionVO snapshot(Long pictureId,User user) {
        Picture picture=picture(pictureId,user,false);
        Long sessionId=transaction.execute(status->{
            // INSERT IGNORE on an existing row takes a shared lock; concurrent readers
            // upgrading it to FOR UPDATE can deadlock. Only insert the first draft row.
            if(!hasSharedPicture(pictureId)) jdbc.update("INSERT IGNORE INTO picture_collaboration(picture_id) VALUES (?)",pictureId);
            Map<String,Object> record=row(pictureId,true);
            Long id=number(record.get("session_id"));
            PictureEditSession current=id==null?null:sessions.loadSession(id);
            if(current==null||!"active".equals(current.getStatus())||current.getExpireTime()!=null&&current.getExpireTime().before(new Date())){
                if(current!=null){JSONObject old=leases.get(pictureId);if(old!=null)leases.release(pictureId,AgentConstant.EDIT_LOCK_MODE_AGENT,old.getLong("userId"),current.getId());}
                current=sessions.initializeShared(picture,user);
                jdbc.update("INSERT INTO picture_collaboration_session(session_id,picture_id) VALUES (?,?)",current.getId(),pictureId);
                jdbc.update("UPDATE picture_collaboration SET session_id=?,editor_id=NULL,client_id=NULL,epoch=epoch+1,lease_until=0 WHERE picture_id=?",current.getId(),pictureId);
            }
            return current.getId();
        });
        return decorate(sessions.getSnapshot(sessionId,user),row(pictureId,false));
    }
    private AgentSessionVO decorate(AgentSessionVO snapshot,Map<String,Object> record) {
        snapshot.setCollaboration(new JSONObject().set("editorId",record.get("editor_id")==null?null:String.valueOf(record.get("editor_id")))
            .set("clientId",record.get("client_id")).set("epoch",number(record.get("epoch"))).set("until",number(record.get("lease_until"))).set("serverTime",System.currentTimeMillis()));
        Long editor=number(record.get("editor_id"));User holder=editor==null?null:users.getById(editor);
        if(holder!=null)snapshot.getCollaboration().put("editorName",holder.getUserName());
        return snapshot;
    }
    public AgentSessionVO sessionSnapshot(Long sessionId,User user) {
        PictureEditSession session=sessions.loadSession(sessionId);
        return decorate(sessions.getSnapshot(sessionId,user),row(session.getPictureId(),false));
    }
    public Map<String,Object> info(Long sessionId) {
        PictureEditSession session=sessions.loadSession(sessionId);
        AgentSessionVO empty=new AgentSessionVO();
        return decorate(empty,row(session.getPictureId(),false)).getCollaboration();
    }
    public PictureEditSession lockPictureEditor(Long pictureId,User user) {
        auth.requireEdit(user,sessions.requirePicture(pictureId));
        List<Map<String,Object>> records=jdbc.queryForList("SELECT * FROM picture_collaboration WHERE picture_id=? FOR UPDATE",pictureId);
        if(records.isEmpty())return null;
        PictureEditSession session=sessions.loadSession(number(records.get(0).get("session_id")));
        verify(records.get(0),user,context(),session,true);
        return session;
    }
    public JSONObject context() {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes))throw failure("缺少协同会话身份");
        String text=((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest().getHeader("X-Picture-Collaboration");
        try {
            JSONObject value=JSONUtil.parseObj(text);
            UUID.fromString(value.getStr("clientId"));
            if(value.getLong("epoch")==null||value.getInt("expectedRevision")==null)throw failure("缺少协同版本或编辑权代次");
            return value;
        }catch(Exception e){throw failure("缺少有效协同会话身份，请从协同工作台操作");}
    }
    private void verify(Map<String,Object> record,User user,JSONObject ctx,PictureEditSession session,boolean revision) {
        if(!Objects.equals(number(record.get("session_id")),session.getId()))throw failure("协同工程已更换，请刷新工作台");
        if(number(record.get("lease_until"))<=System.currentTimeMillis()||!Objects.equals(number(record.get("editor_id")),user.getId())||!Objects.equals(record.get("client_id"),ctx.getStr("clientId"))||!Objects.equals(number(record.get("epoch")),ctx.getLong("epoch")))throw failure("编辑权已失效或已交接，请重新获取编辑权");
        JSONObject lease=leases.get(session.getPictureId());
        if(lease==null||!AgentConstant.EDIT_LOCK_MODE_AGENT.equals(lease.getStr("mode"))||!Objects.equals(lease.getLong("userId"),user.getId())||!Objects.equals(lease.getLong("sessionId"),session.getId()))throw failure("编辑租约已失效，请重新获取编辑权");
        if(!"active".equals(session.getStatus()))throw failure("当前草稿已结束，请载入新的协同工程");
        if(revision&&!Objects.equals(session.getRevision(),ctx.getInt("expectedRevision")))throw failure("画布版本冲突：草稿已保留，请载入最新版本");
    }
    public void requireEditor(PictureEditSession session,User user) {
        verify(row(session.getPictureId(),false),user,context(),session,true);
    }
    public void lockEditor(PictureEditSession session,User user) {
        auth.requireEdit(user,sessions.requirePicture(session.getPictureId()));
        verify(row(session.getPictureId(),true),user,context(),sessions.loadSession(session.getId()),true);
    }
    private String commitDigest(PictureEditSession session,User user,Long assetId,Long editVersion,JSONObject ctx) {
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(user.getId()+":"+ctx.getStr("clientId")+":"+ctx.getLong("epoch")+":"+ctx.getInt("expectedRevision")+":"+assetId+":"+editVersion);
    }
    /** Persist the accepted request in the same transaction as its formal version. */
    public void recordCommit(PictureEditSession session,User user,Long assetId,Long editVersion) {
        JSONObject ctx=context();
        jdbc.update("INSERT INTO picture_collaboration_request(session_id,request_key,digest) VALUES (?,?,?)",session.getId(),"commit:"+ctx.getStr("clientId"),commitDigest(session,user,assetId,editVersion,ctx));
    }
    /** A replay returns an existing version only; it never reacquires or modifies a draft. */
    public void requireCommitReplay(PictureEditSession session,User user,Long assetId,Long editVersion) {
        picture(session.getPictureId(),user,true);
        JSONObject ctx=context();
        Long selected=assetId==null?session.getFinalAssetId():assetId;
        List<Map<String,Object>> records=jdbc.queryForList("SELECT digest FROM picture_collaboration_request WHERE session_id=? AND request_key=?",session.getId(),"commit:"+ctx.getStr("clientId"));
        if(records.isEmpty()||!Objects.equals(records.get(0).get("digest"),commitDigest(session,user,selected,editVersion,ctx)))throw failure("提交请求已改变，请载入最新协同工程");
    }
    public AgentSessionVO lease(Long pictureId,User user,String action,JSONObject ctx,Long targetId) {
        picture(pictureId,user,true);
        transaction.execute(status->{
            Map<String,Object> record=row(pictureId,true);PictureEditSession session=sessions.loadSession(number(record.get("session_id")));
            String client=ctx.getStr("clientId");try{UUID.fromString(client);}catch(Exception e){throw failure("无效客户端身份");}
            long until=number(record.get("lease_until")),epoch=number(record.get("epoch")),now=System.currentTimeMillis();
            if("acquire".equals(action)){
                if(!jdbc.queryForList("SELECT client_id FROM picture_annotation_member WHERE picture_id=? AND lease_until>?",pictureId,now).isEmpty())throw failure("成员正在多人画布编辑，请等待成员退出后获取独占编辑权");
                if(!"active".equals(session.getStatus())||session.getExpireTime()!=null&&session.getExpireTime().before(new Date()))throw failure("草稿已结束，请刷新工作台");
                if(until>now&&(!Objects.equals(number(record.get("editor_id")),user.getId())||record.get("client_id")!=null&&!Objects.equals(record.get("client_id"),client)))throw failure("其他会话正在编辑或已预约接收编辑权");
                JSONObject held=leases.tryAcquire(pictureId,AgentConstant.EDIT_LOCK_MODE_AGENT,user.getId(),session.getId());
                if(held==null)throw failure("图片正被其他编辑工作台占用");
                boolean same=until>now&&Objects.equals(record.get("client_id"),client);
                jdbc.update("UPDATE picture_collaboration SET editor_id=?,client_id=?,epoch=?,lease_until=? WHERE picture_id=?",user.getId(),client,same?epoch:epoch+1,now+TTL,pictureId);
            }else{
                verify(record,user,ctx,session,false);
                if("renew".equals(action)){
                    if(!leases.renew(pictureId,AgentConstant.EDIT_LOCK_MODE_AGENT,user.getId(),session.getId()))throw failure("编辑租约已到期");
                    jdbc.update("UPDATE picture_collaboration SET lease_until=? WHERE picture_id=?",now+TTL,pictureId);
                }else if("release".equals(action)||"handoff".equals(action)){
                    if("handoff".equals(action)){User target=targetId==null?null:users.getById(targetId);if(target==null)throw failure("接收成员不存在");auth.requireEdit(target,sessions.requirePicture(pictureId));}
                    leases.release(pictureId,AgentConstant.EDIT_LOCK_MODE_AGENT,user.getId(),session.getId());
                    jdbc.update("UPDATE picture_collaboration SET editor_id=?,client_id=NULL,epoch=epoch+1,lease_until=? WHERE picture_id=?","handoff".equals(action)?targetId:null,"handoff".equals(action)?now+TTL:0,pictureId);
                }else throw failure("无效编辑权操作");
            }
            return null;
        });
        changed(pictureId);return sessionSnapshot(number(row(pictureId,false).get("session_id")),user);
    }
    public void captureRun(PictureAgentRun run) {
        if(!shared(run.getEditSessionId()))return;
        JSONObject ctx=context();
        jdbc.update("INSERT INTO picture_collaboration_run(run_id,session_id,user_id,client_id,epoch) VALUES (?,?,?,?,?)",run.getId(),run.getEditSessionId(),run.getUserId(),ctx.getStr("clientId"),ctx.getLong("epoch"));
    }
    public void requireRun(PictureAgentRun run,User user,boolean allowOld) {
        PictureEditSession session=sessions.requireSession(run.getEditSessionId(),user,true);
        if(!allowOld&&!runCurrent(run.getId(),session))throw failure("运行来自旧编辑会话，请重新发送或重新规划");
    }
    private boolean runCurrent(Long runId,PictureEditSession session) {
        List<Map<String,Object>> contexts=jdbc.queryForList("SELECT * FROM picture_collaboration_run WHERE run_id=?",runId);
        if(contexts.isEmpty())return false;
        Map<String,Object> run=contexts.get(0),record=row(session.getPictureId(),false);
        JSONObject lease=leases.get(session.getPictureId());
        return Objects.equals(number(record.get("session_id")),session.getId())&&"active".equals(session.getStatus())&&number(record.get("lease_until"))>System.currentTimeMillis()&&Objects.equals(record.get("client_id"),run.get("client_id"))&&Objects.equals(number(record.get("epoch")),number(run.get("epoch")))&&Objects.equals(number(record.get("editor_id")),number(run.get("user_id")))&&lease!=null&&AgentConstant.EDIT_LOCK_MODE_AGENT.equals(lease.getStr("mode"))&&Objects.equals(lease.getLong("sessionId"),session.getId())&&Objects.equals(lease.getLong("userId"),number(run.get("user_id")));
    }
    public void checkToolStart(PictureToolRun run,PictureEditSession session) {
        if(shared(session.getId())&&!runCurrent(run.getAgentRunId(),session))throw failure("编辑权已改变，未执行旧计划；请重新发送指令");
    }
    public void applyToolResult(PictureToolRun run,Integer inputRevision,Map<String,Object> result) {
        PictureEditSession initial=sessions.loadSession(run.getEditSessionId());
        transaction.execute(status->{
            row(initial.getPictureId(),true);PictureEditSession current=sessions.loadSession(initial.getId());
            User operator=users.getById(run.getUserId());
            boolean valid=runCurrent(run.getAgentRunId(),current)&&Objects.equals(inputRevision,current.getRevision());
            if(valid){try{auth.requireEdit(operator,sessions.requirePicture(current.getPictureId()));}catch(BusinessException e){valid=false;}}
            if(!valid){result.put("pending",true);result.put("applied",false);result.put("pendingReason","编辑权或画布版本已变化，结果已保留在资产墙，请手动采用");return null;}
            boolean applied=documents.applyToolResult(current,run,result);result.put("applied",applied);return null;
        });
        changed(initial.getPictureId());
    }
    public AgentSessionVO saveCanvas(Long pictureId,User user,JSONObject ctx,String stateText,byte[] bytes) throws Exception {
        picture(pictureId,user,true);Map<String,Object> initial=row(pictureId,false);PictureEditSession session=sessions.loadSession(number(initial.get("session_id")));verify(initial,user,ctx,session,false);
        JSONObject state=JSONUtil.parseObj(stateText);validateState(state,session);
        BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(bytes));if(decoded==null||decoded.getWidth()>4096||decoded.getHeight()>4096||bytes.length>10*1024*1024)throw failure("草稿图片无效或过大");
        String key=ctx.getStr("idempotencyKey");if(key==null||key.length()<8||key.length()>100)throw failure("缺少幂等键");
        String digest=cn.hutool.crypto.digest.DigestUtil.sha256Hex(stateText+":"+cn.hutool.crypto.digest.DigestUtil.sha256Hex(bytes)+":"+user.getId()+":"+ctx);
        List<Map<String,Object>> prior=jdbc.queryForList("SELECT digest FROM picture_collaboration_request WHERE session_id=? AND request_key=?",session.getId(),key);
        if(!prior.isEmpty()){if(!Objects.equals(prior.get(0).get("digest"),digest))throw failure("幂等键已用于其他草稿");return sessionSnapshot(session.getId(),user);}
        PictureAgentAsset asset=assets.createFromBytes(session.getId(),pictureId,user.getId(),AgentAssetKindEnum.CANDIDATE.getValue(),"协同手动画布","png",bytes);
        transaction.execute(status->{
            Map<String,Object> locked=row(pictureId,true);PictureEditSession latest=sessions.loadSession(session.getId());auth.requireEdit(user,sessions.requirePicture(pictureId));verify(locked,user,ctx,latest,false);
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT digest FROM picture_collaboration_request WHERE session_id=? AND request_key=?",session.getId(),key);
            if(!previous.isEmpty()){if(!Objects.equals(previous.get(0).get("digest"),digest))throw failure("幂等键已用于其他草稿");return null;}
            verify(locked,user,ctx,latest,true);
            JSONObject document=documents.adoptIntoDocument(documents.parseDocument(latest),asset);document.set("quickEditorState",state);
            documents.applyEdit(latest,"collaborative_canvas",stateText,document,asset,null);
            jdbc.update("INSERT INTO picture_collaboration_request(session_id,request_key,digest) VALUES (?,?,?)",session.getId(),key,digest);return null;
        });
        changed(pictureId);return sessionSnapshot(session.getId(),user);
    }
    private void validateState(JSONObject state,PictureEditSession session) {
        String url=state.getStr("baseImageUrl");boolean known=assets.listBySession(session.getId(),null).stream().anyMatch(asset->Objects.equals(asset.getUrl(),url));
        if(!known)throw failure("底图不属于当前协同工程");
        if(state.toString().length()>1024*1024||state.getJSONArray("strokes")==null||state.getJSONArray("overlays")==null||state.getJSONArray("strokes").size()>1000||state.getJSONArray("overlays").size()>500)throw failure("画布状态无效或过大");
    }
    public byte[] canvas(Long pictureId,User user) {
        picture(pictureId,user,false);PictureEditSession session=sessions.loadSession(number(row(pictureId,false).get("session_id")));
        return documents.flatten(session,documents.parseDocument(session));
    }
    public byte[] assetContent(Long pictureId,Long assetId,User user) {
        picture(pictureId,user,false);
        Long sessionId=number(row(pictureId,false).get("session_id"));
        return assets.loadBytes(assets.getInSession(sessionId,assetId));
    }
    /** Bake all Agent layers into an immutable base before returning to the manual editor. */
    public AgentSessionVO manual(Long pictureId,User user) {
        picture(pictureId,user,true);
        JSONObject projection=transaction.execute(status->{
            Map<String,Object> locked=row(pictureId,true);PictureEditSession session=sessions.loadSession(number(locked.get("session_id")));
            verify(locked,user,context(),session,true);
            JSONObject document=documents.parseDocument(session);
            if(document.getJSONObject("quickEditorState")!=null)return document.getJSONObject("quickEditorState");
            String source="协同画布预览:r"+session.getRevision();
            PictureAgentAsset asset=assets.listBySession(session.getId(),null).stream().filter(a->source.equals(a.getSource())).findFirst().orElse(null);
            if(asset==null)asset=assets.createFromBytes(session.getId(),pictureId,user.getId(),AgentAssetKindEnum.CANDIDATE.getValue(),source,"png",documents.flatten(session,document));
            JSONObject quick=new JSONObject().set("baseImageUrl",asset.getUrl()).set("scale",1).set("rotation",0).set("flipX",1).set("flipY",1)
                .set("filters",new JSONObject().set("brightness",0).set("contrast",0).set("saturation",0).set("grayscale",0).set("sepia",0)).set("strokes",new cn.hutool.json.JSONArray()).set("overlays",new cn.hutool.json.JSONArray());
            return quick;
        });
        AgentSessionVO snapshot=sessionSnapshot(number(row(pictureId,false).get("session_id")),user);
        snapshot.getDocument().put("quickEditorState",projection);
        return snapshot;
    }
    public void changed(Long pictureId) {publisher.publishEvent(new PictureCollaborationChanged(pictureId));}
}
