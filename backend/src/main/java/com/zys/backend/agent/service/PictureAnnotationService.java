package com.zys.backend.agent.service;

import cn.hutool.json.*;
import com.zys.backend.constant.AgentConstant;
import com.zys.backend.exception.*;
import com.zys.backend.model.entity.*;
import com.zys.backend.model.enums.AgentAssetKindEnum;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.*;

@Service
public class PictureAnnotationService {
    @Resource private JdbcTemplate jdbc;
    @Resource private TransactionTemplate transaction;
    @Resource private PictureCollaborationService collaboration;
    @Resource private AgentSessionService sessions;
    @Resource private AgentAuthService auth;
    @Resource private AgentAssetService assets;
    @Resource private LayerDocumentService documents;
    @Resource private EditLeaseService leases;
    private static final int LIMIT=5;
    private static long n(Object v){return v==null?0:((Number)v).longValue();}
    private static void require(boolean b,String s){if(!b)throw new BusinessException(ErrorCode.OPERATION_ERROR,s);}
    private Map<String,Object> room(Long id){return jdbc.queryForMap("SELECT * FROM picture_collaboration WHERE picture_id=? FOR UPDATE",id);}
    private PictureEditSession session(Map<String,Object> room){PictureEditSession s=sessions.loadSession(n(room.get("session_id")));require("active".equals(s.getStatus())&&s.getExpireTime().after(new Date()),"共享草稿已结束，请重新载入");return s;}
    private List<Map<String,Object>> members(Long id){return jdbc.queryForList("SELECT CAST(m.user_id AS CHAR) userId,m.client_id clientId,m.epoch,m.lease_until AS `until`,u.userName name FROM picture_annotation_member m LEFT JOIN user u ON u.id=m.user_id WHERE m.picture_id=? AND m.lease_until>?",id,System.currentTimeMillis());}
    public JSONObject view(Long pictureId,User user){
        com.zys.backend.agent.model.vo.AgentSessionVO snapshot=collaboration.snapshot(pictureId,user);
        return transaction.execute(status->{Map<String,Object> r=room(pictureId);PictureEditSession s=session(r);return snapshot(pictureId,s,r);});
    }
    private JSONObject snapshot(Long id,PictureEditSession s,Map<String,Object> room){
        List<Map<String,Object>> records=jdbc.queryForList("SELECT * FROM picture_annotation_document WHERE session_id=?",s.getId());
        JSONObject state=records.isEmpty()||n(records.get(0).get("applied_revision"))!=s.getRevision()?new JSONObject().set("objects",new JSONArray()):JSONUtil.parseObj(records.get(0).get("document"));
        return new JSONObject().set("sessionId",String.valueOf(s.getId())).set("revision",s.getRevision()).set("state",state).set("members",members(id)).set("limit",LIMIT).set("exclusiveUntil",n(room.get("lease_until"))).set("serverTime",System.currentTimeMillis());
    }
    public JSONObject lease(Long id,User user,JSONObject input){
        collaboration.snapshot(id,user);auth.requireEdit(user,sessions.requirePicture(id));
        String client=input.getStr("clientId");try{UUID.fromString(client);}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR,"无效客户端身份");}
        JSONObject result=transaction.execute(status->{
            Map<String,Object> r=room(id);PictureEditSession s=session(r);long now=System.currentTimeMillis();
            List<Map<String,Object>> found=jdbc.queryForList("SELECT * FROM picture_annotation_member WHERE picture_id=? AND client_id=?",id,client);
            Map<String,Object> current=found.isEmpty()?Collections.emptyMap():found.get(0);
            boolean alive=n(current.get("lease_until"))>now&&n(current.get("session_id"))==s.getId();
            String action=input.getStr("action");
            if("acquire".equals(action)){
                require(n(r.get("lease_until"))<=now,"有人正在独占编辑，请稍后加入多人画布");
                require(alive||members(id).size()<LIMIT,"多人画布已满（5 个窗口），当前可继续观看");
                require(current.isEmpty()||n(current.get("user_id"))==user.getId(),"客户端身份不属于当前成员");
                require(leases.tryAcquire(id,AgentConstant.EDIT_LOCK_MODE_AGENT,0L,s.getId())!=null,"图片正被其他工作台编辑");
                long epoch=alive?n(current.get("epoch")):n(current.get("epoch"))+1;
                jdbc.update("INSERT INTO picture_annotation_member(picture_id,client_id,user_id,session_id,epoch,lease_until) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE session_id=VALUES(session_id),epoch=VALUES(epoch),lease_until=VALUES(lease_until)",id,client,user.getId(),s.getId(),epoch,now+60000);
                initialize(s,user);
                return snapshot(id,s,r).set("epoch",epoch);
            }
            verify(current,s,user,input);
            if("renew".equals(action)){
                require(leases.renew(id,AgentConstant.EDIT_LOCK_MODE_AGENT,0L,s.getId()),"多人编辑租约已失效，请重新加入");
                jdbc.update("UPDATE picture_annotation_member SET lease_until=? WHERE picture_id=? AND client_id=?",now+60000,id,client);
            }else if("release".equals(action)){
                jdbc.update("UPDATE picture_annotation_member SET lease_until=0,epoch=epoch+1 WHERE picture_id=? AND client_id=?",id,client);
                if(members(id).isEmpty())leases.release(id,AgentConstant.EDIT_LOCK_MODE_AGENT,0L,s.getId());
            }else require(false,"无效多人编辑操作");
            return snapshot(id,s,r).set("epoch",n(current.get("epoch"))+("release".equals(action)?1:0));
        });collaboration.changed(id);return result;
    }
    private void initialize(PictureEditSession s,User user){
        List<Map<String,Object>> found=jdbc.queryForList("SELECT applied_revision FROM picture_annotation_document WHERE session_id=?",s.getId());
        if(!found.isEmpty()&&n(found.get(0).get("applied_revision"))==s.getRevision())return;
        PictureAgentAsset base=assets.createFromBytes(s.getId(),s.getPictureId(),user.getId(),AgentAssetKindEnum.CANDIDATE.getValue(),"多人画布底图","png",documents.currentCanvasBytes(s));
        JSONObject state=new JSONObject().set("baseAssetId",String.valueOf(base.getId())).set("width",base.getWidth()).set("height",base.getHeight()).set("objects",new JSONArray());
        jdbc.update("INSERT INTO picture_annotation_document(session_id,applied_revision,document) VALUES (?,?,?) ON DUPLICATE KEY UPDATE applied_revision=VALUES(applied_revision),document=VALUES(document)",s.getId(),s.getRevision(),state.toString());
    }
    private void verify(Map<String,Object> current,PictureEditSession s,User user,JSONObject input){
        require(n(current.get("lease_until"))>System.currentTimeMillis()&&n(current.get("user_id"))==user.getId()&&Objects.equals(String.valueOf(s.getId()),input.getStr("sessionId"))&&n(current.get("session_id"))==s.getId()&&Objects.equals(n(current.get("epoch")),input.getLong("epoch")),"多人编辑资格已失效，请重新加入；本地操作已保留");
    }
    public JSONObject operate(Long id,User user,JSONObject input){
        auth.requireEdit(user,sessions.requirePicture(id));String key=input.getStr("idempotencyKey","");require(key.length()>=8&&key.length()<=100,"缺少有效幂等键");
        String digest=cn.hutool.crypto.digest.DigestUtil.sha256Hex(user.getId()+":"+input);
        JSONObject result=transaction.execute(status->{
            Map<String,Object> r=room(id);PictureEditSession s=session(r);
            List<Map<String,Object>> prior=jdbc.queryForList("SELECT digest FROM picture_collaboration_request WHERE session_id=? AND request_key=?",s.getId(),"annotation:"+key);
            if(!prior.isEmpty()){require(digest.equals(prior.get(0).get("digest")),"幂等键已用于其他操作");return snapshot(id,s,r);}
            List<Map<String,Object>> found=jdbc.queryForList("SELECT * FROM picture_annotation_member WHERE picture_id=? AND client_id=?",id,input.getStr("clientId"));require(!found.isEmpty(),"请先加入多人画布");verify(found.get(0),s,user,input);
            require(n(r.get("lease_until"))<=System.currentTimeMillis(),"有人正在独占编辑");
            JSONObject held=leases.get(id);require(held!=null&&Objects.equals(held.getLong("userId"),0L)&&Objects.equals(held.getLong("sessionId"),s.getId()),"多人编辑租约已失效，请重新加入");
            Map<String,Object> stored=jdbc.queryForMap("SELECT * FROM picture_annotation_document WHERE session_id=?",s.getId());require(n(stored.get("applied_revision"))==s.getRevision(),"底图已经更新，请重新加入多人画布");
            JSONObject next=PictureAnnotationDocument.apply(JSONUtil.parseObj(stored.get("document")),input.getJSONArray("operations"));
            PictureAgentAsset base=assets.getInSession(s.getId(),next.getLong("baseAssetId"));
            PictureAgentAsset merged=assets.createFromBytes(s.getId(),id,user.getId(),AgentAssetKindEnum.CANDIDATE.getValue(),"多人画布合成","png",PictureAnnotationDocument.render(assets.loadBytes(base),next));
            documents.applyEdit(s,"shared_annotations",null,documents.adoptIntoDocument(documents.parseDocument(s),merged),merged,null);
            jdbc.update("UPDATE picture_annotation_document SET applied_revision=?,document=? WHERE session_id=?",s.getRevision(),next.toString(),s.getId());
            jdbc.update("INSERT INTO picture_collaboration_request(session_id,request_key,digest) VALUES (?,?,?)",s.getId(),"annotation:"+key,digest);
            return snapshot(id,s,r);
        });collaboration.changed(id);return result;
    }
}
