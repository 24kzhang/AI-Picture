package com.zys.backend.api.aliyunai;
import com.sun.net.httpserver.HttpServer;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class QwenImageGeneratorTest {
    private HttpServer server;private QwenImageGenerator generator;private String output="/qwen-image/outputs/real_scene.png";private final AtomicReference<String> body=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/qwen-image/api/generate",exchange->{body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] bytes=("{\"id\":\"receipt-1\",\"url\":\""+output+"\"}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.createContext("/qwen-image/outputs/real_scene.png",exchange->{exchange.getResponseHeaders().add("Location","http://127.0.0.1/private");exchange.sendResponseHeaders(302,-1);exchange.close();});server.start();
        generator=new QwenImageGenerator();ReflectionTestUtils.setField(generator,"baseUrl","http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void sendsJsonWithChinesePromptAndRequestedSize(){QwenImageGenerator.Receipt receipt=generator.generate("湖边咖啡店，真实场景",1280,720);assertEquals("receipt-1",receipt.getId());assertEquals("湖边咖啡店，真实场景",JSONUtil.parseObj(body.get()).getStr("prompt"));assertEquals(1280,JSONUtil.parseObj(body.get()).getInt("width"));assertEquals(720,JSONUtil.parseObj(body.get()).getInt("height"));assertEquals(30,JSONUtil.parseObj(body.get()).getInt("steps"));}
    @Test void rejectsProviderUrlOutsideOutputDirectory(){output="http://127.0.0.1/private";assertThrows(com.zys.backend.exception.BusinessException.class,()->generator.generate("咖啡店",1024,1024));}
    @Test void rejectsDownloadRedirectInsteadOfFollowingIt(){String url=generator.generate("咖啡店",1024,1024).getUrl();assertThrows(com.zys.backend.exception.BusinessException.class,()->generator.download(url));}
    @Test void rejectsArbitraryDownloadUrl(){assertThrows(com.zys.backend.exception.BusinessException.class,()->generator.download("http://127.0.0.1/private"));}
}
