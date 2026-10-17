package com.zys.backend.moderation;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.dto.picture.PictureReviewRequest;
import com.zys.backend.service.impl.PictureServiceImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
class PictureReviewFlowTest {
    @Test void newContentWaitsForAiEvenWhenAnAdministratorUploadsIt(){
        Picture picture=new Picture();User user=new User();user.setUserRole("admin");
        new PictureServiceImpl().fillReviewParams(picture,user);
        assertEquals(0,picture.getReviewStatus());assertEquals(0L,picture.getReviewerId());assertEquals("等待AI审核",picture.getReviewMessage());
    }
    @Test void administratorCanConfirmOrOverrideBothAiOutcomes(){
        for(int initial:new int[]{1,2})for(int decision:new int[]{1,2}){
            PictureServiceImpl service=spy(new PictureServiceImpl());Picture old=new Picture();old.setId(1L);old.setReviewStatus(initial);old.setReviewerId(0L);
            doReturn(old).when(service).getById(1L);doReturn(true).when(service).updateById(any(Picture.class));
            PictureReviewRequest input=new PictureReviewRequest();input.setId(1L);input.setReviewStatus(decision);input.setReviewMessage("人工复审");User admin=new User();admin.setId(7L);
            assertDoesNotThrow(()->service.doPictureReview(input,admin));
            verify(service).updateById(org.mockito.ArgumentMatchers.argThat((Picture p)->p.getReviewStatus()==decision&&Long.valueOf(7).equals(p.getReviewerId())));
        }
    }
}
