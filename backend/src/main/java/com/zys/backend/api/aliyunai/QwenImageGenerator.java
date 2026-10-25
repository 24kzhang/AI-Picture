package com.zys.backend.api.aliyunai;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 文生图与下载分离，先保存模型回执，再转存产物。 */
@Component
public class QwenImageGenerator {
    @Value("${qwenImage.baseUrl:http://172.18.20.44:30066}") private String baseUrl;
    @Getter @AllArgsConstructor public static class Receipt { private final String id; private final String url; }

    public Receipt generate(String prompt,int width,int height) {
        JSONObject body=new JSONObject().set("prompt",prompt).set("width",width).set("height",height).set("steps",30).set("guidance_scale",4);
        try(HttpResponse response=HttpRequest.post(baseUrl.replaceAll("/+$","")+"/qwen-image/api/generate")
                .header("Content-Type","application/json").body(body.toString()).timeout(1800000).execute()) {
            if(!response.isOk())throw new BusinessException(ErrorCode.OPERATION_ERROR,"生图服务返回 HTTP "+response.getStatus());
            JSONObject result=JSONUtil.parseObj(response.body());String path=result.getStr("url");
            if(path==null||!path.matches("/qwen-image/outputs/[A-Za-z0-9_-]+\\.png"))throw new BusinessException(ErrorCode.OPERATION_ERROR,"生图服务未返回有效图片地址");
            return new Receipt(result.getStr("id"),baseUrl.replaceAll("/+$","")+path);
        }
    }
    public byte[] download(String url) {
        String prefix=baseUrl.replaceAll("/+$","")+"/qwen-image/outputs/";
        if(url==null||!url.startsWith(prefix)||!url.substring(prefix.length()).matches("[A-Za-z0-9_-]+\\.png"))throw new BusinessException(ErrorCode.PARAMS_ERROR,"无效生图产物地址");
        try(HttpResponse response=HttpRequest.get(url).setFollowRedirects(false).timeout(60000).execute()) {
            if(!response.isOk())throw new BusinessException(ErrorCode.OPERATION_ERROR,"读取生图结果失败");
            byte[] bytes=response.bodyBytes();
            if(bytes.length==0||bytes.length>10*1024*1024)throw new BusinessException(ErrorCode.OPERATION_ERROR,"生成图片为空或超过 10 MiB");
            return bytes;
        }
    }
}
