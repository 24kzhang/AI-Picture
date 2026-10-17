package com.zys.backend.agent.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.UUID;

/** Object versions include tombstones so delayed messages cannot resurrect deleted objects. */
public final class PictureAnnotationDocument {
    private PictureAnnotationDocument() {}
    private static void require(boolean value,String text){if(!value)throw new BusinessException(ErrorCode.OPERATION_ERROR,text);}
    private static double number(JSONObject object,String key,double min,double max){Double n=object.getDouble(key);require(n!=null&&Double.isFinite(n)&&n>=min&&n<=max,"无效画布坐标或尺寸");return n;}
    public static JSONObject apply(JSONObject source,JSONArray operations){
        require(operations!=null&&operations.size()>0&&operations.size()<=30,"一次可提交 1 至 30 个画布操作");
        JSONObject result=JSONUtil.parseObj(source.toString());JSONArray objects=result.getJSONArray("objects");
        for(int i=0;i<operations.size();i++){
            JSONObject op=operations.getJSONObject(i);String id=op.getStr("id");
            try{UUID.fromString(id);}catch(Exception e){throw new BusinessException(ErrorCode.PARAMS_ERROR,"无效图层标识");}
            JSONObject old=null;int index=-1;
            for(int j=0;j<objects.size();j++)if(id.equals(objects.getJSONObject(j).getStr("id"))){old=objects.getJSONObject(j);index=j;break;}
            Integer expected=op.getInt("expectedVersion");int version=old==null?0:old.getInt("version");
            require(expected!=null&&expected==version,"此画笔或贴图已被其他成员修改，请核对最新内容；本地操作已保留");
            require(old==null||!old.getBool("deleted",false),"图层已经删除，不能用旧操作恢复");
            JSONObject next;
            if(op.getBool("delete",false)){require(old!=null,"图层不存在");next=new JSONObject().set("deleted",true);}
            else {
                next=op.getJSONObject("value");require(next!=null,"缺少画布对象");next=JSONUtil.parseObj(next.toString());validate(next);
            }
            next.set("id",id).set("version",version+1);
            if(index<0)objects.add(next);else objects.set(index,next);
        }
        require(objects.size()<=500&&result.toString().length()<=5*1024*1024,"共享画布已达到大小上限，请结束多人编辑后合并画布");
        return result;
    }
    private static void validate(JSONObject value){
        String kind=value.getStr("kind");
        if("stroke".equals(kind)){
            JSONArray points=value.getJSONArray("points");require(points!=null&&points.size()>0&&points.size()<=2000,"每笔最多 2000 个采样点");
            require(value.getStr("color","").matches("#[0-9a-fA-F]{6}"),"无效画笔颜色");number(value,"width",0.0001,0.15);
            for(int i=0;i<points.size();i++){number(points.getJSONObject(i),"x",0,1);number(points.getJSONObject(i),"y",0,1);}
        }else if("raster".equals(kind)){
            number(value,"x",0,1);number(value,"y",0,1);number(value,"width",0.001,1);number(value,"height",0.001,1);
            raster(value);
        }else require(false,"多人模式仅支持画笔、文字和贴图");
    }
    private static BufferedImage raster(JSONObject value){
        String data=value.getStr("png","");require(data.startsWith("data:image/png;base64,")&&data.length()<=350000,"贴图必须为不超过 256KB 的 PNG");
        try{
            byte[] bytes=Base64.getDecoder().decode(data.substring(22));
            try(javax.imageio.stream.ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
                java.util.Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(input);require(readers.hasNext(),"无法解码贴图");
                javax.imageio.ImageReader reader=readers.next();try{reader.setInput(input);require("png".equalsIgnoreCase(reader.getFormatName())&&reader.getWidth(0)<=1024&&reader.getHeight(0)<=1024,"贴图尺寸不能超过 1024 像素");return reader.read(0);}finally{reader.dispose();}
            }
        }catch(IOException|IllegalArgumentException e){throw new BusinessException(ErrorCode.PARAMS_ERROR,"贴图 PNG 无效");}
    }
    public static byte[] render(byte[] base,JSONObject state){
        try{
            BufferedImage image=ImageIO.read(new ByteArrayInputStream(base));require(image!=null&&image.getWidth()<=4096&&image.getHeight()<=4096,"底图无效或过大");
            BufferedImage canvas=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_ARGB);Graphics2D g=canvas.createGraphics();
            try{
                g.setComposite(AlphaComposite.Src);g.drawImage(image,0,0,null);g.setComposite(AlphaComposite.SrcOver);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                for(Object raw:state.getJSONArray("objects")){
                    JSONObject o=JSONUtil.parseObj(raw);if(o.getBool("deleted",false))continue;
                    if("stroke".equals(o.getStr("kind"))){
                        JSONArray points=o.getJSONArray("points");Path2D path=new Path2D.Double();
                        for(int i=0;i<points.size();i++){JSONObject p=points.getJSONObject(i);double x=p.getDouble("x")*canvas.getWidth(),y=p.getDouble("y")*canvas.getHeight();if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}
                        if(points.size()==1){JSONObject p=points.getJSONObject(0);path.lineTo(p.getDouble("x")*canvas.getWidth()+.1,p.getDouble("y")*canvas.getHeight()+.1);}
                        g.setColor(Color.decode(o.getStr("color")));g.setStroke(new BasicStroke((float)(o.getDouble("width")*canvas.getWidth()),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(path);
                    }else{
                        int w=Math.max(1,(int)Math.round(o.getDouble("width")*canvas.getWidth())),h=Math.max(1,(int)Math.round(o.getDouble("height")*canvas.getHeight()));
                        g.drawImage(raster(o),(int)Math.round(o.getDouble("x")*canvas.getWidth()-w/2.0),(int)Math.round(o.getDouble("y")*canvas.getHeight()-h/2.0),w,h,null);
                    }
                }
            }finally{g.dispose();}
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(canvas,"png",bytes);return bytes.toByteArray();
        }catch(IOException e){throw new BusinessException(ErrorCode.OPERATION_ERROR,"共享画布合成失败");}
    }
}
