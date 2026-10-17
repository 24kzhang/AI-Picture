package com.zys.backend.moderation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QwenModerationClientTest {
    @Test void passesOnlyWhenAllThreeCategoriesAreFalse(){
        assertEquals(1,QwenModerationClient.parse("{\"sexual\":false,\"violence\":false,\"gore\":false}").status);
        for(String key:new String[]{"sexual","violence","gore"})assertEquals(2,QwenModerationClient.parse("{\"sexual\":false,\"violence\":false,\"gore\":false}".replace("\""+key+"\":false","\""+key+"\":true")).status);
    }
    @Test void neverPassesMissingOrStringBooleanResults(){
        assertThrows(Exception.class,()->QwenModerationClient.parse("{}"));
        assertThrows(Exception.class,()->QwenModerationClient.parse("{\"sexual\":\"false\",\"violence\":false,\"gore\":false}"));
    }
}
