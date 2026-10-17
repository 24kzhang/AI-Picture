package com.zys.backend.agent;
import cn.hutool.json.*;
import com.zys.backend.agent.service.PictureAnnotationDocument;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
class PictureAnnotationDocumentTest {
    private JSONObject blank(){return new JSONObject().set("objects",new JSONArray());}
    private JSONObject stroke(String id,int version,String color){return new JSONObject().set("id",id).set("expectedVersion",version).set("value",new JSONObject().set("kind","stroke").set("color",color).set("width",.1).set("points",new JSONArray().put(new JSONObject().set("x",.2).set("y",.2)).put(new JSONObject().set("x",.7).set("y",.2))));}
    @Test void independentObjectsMergeAndStaleEditsDoNotOverwrite(){
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        JSONObject state=PictureAnnotationDocument.apply(blank(),new JSONArray().put(stroke(a,0,"#ff0000")));
        state=PictureAnnotationDocument.apply(state,new JSONArray().put(stroke(b,0,"#00ff00")));
        assertEquals(2,state.getJSONArray("objects").size());
        final JSONObject current=state;
        assertThrows(RuntimeException.class,()->PictureAnnotationDocument.apply(current,new JSONArray().put(stroke(a,0,"#000000"))));
        JSONObject deleted=PictureAnnotationDocument.apply(state,new JSONArray().put(new JSONObject().set("id",a).set("expectedVersion",1).set("delete",true)));
        assertThrows(RuntimeException.class,()->PictureAnnotationDocument.apply(deleted,new JSONArray().put(stroke(a,2,"#ffffff"))));
        assertEquals(1,current.getJSONArray("objects").getJSONObject(0).getInt("version"));
    }
    @Test void compositionContainsBothStrokesAndPreservesPixelsOutsideThem() throws Exception {
        BufferedImage base=new BufferedImage(100,100,BufferedImage.TYPE_INT_ARGB);base.setRGB(90,90,0x6688aacc);ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(base,"png",bytes);
        JSONObject red=stroke(UUID.randomUUID().toString(),0,"#ff0000"),green=stroke(UUID.randomUUID().toString(),0,"#00ff00");
        green.getJSONObject("value").getJSONArray("points").getJSONObject(0).set("y",.6);green.getJSONObject("value").getJSONArray("points").getJSONObject(1).set("y",.6);
        JSONObject state=PictureAnnotationDocument.apply(blank(),new JSONArray().put(red).put(green));
        BufferedImage output=ImageIO.read(new ByteArrayInputStream(PictureAnnotationDocument.render(bytes.toByteArray(),state)));
        assertEquals(0xffff0000,output.getRGB(40,20));assertEquals(0xff00ff00,output.getRGB(40,60));assertEquals(base.getRGB(90,90),output.getRGB(90,90));
    }
}
