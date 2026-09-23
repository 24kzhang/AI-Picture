package com.zys.backend.model.vo;

import com.zys.backend.model.entity.Picture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PictureVOTest {

    @Test
    void shouldExposeEmptyTagsWhenStoredTagsAreNull() {
        Picture picture = new Picture();
        picture.setTags(null);

        PictureVO result = PictureVO.objToVo(picture);

        assertNotNull(result.getTags());
        assertTrue(result.getTags().isEmpty());
    }
}
