package com.zys.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zys.backend.api.aliyunai.model.CreateOutPaintingTaskResponse;
import com.zys.backend.model.dto.picture.*;
import com.zys.backend.model.entity.Picture;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.vo.PictureVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
* @author 10415
* @description 针对表【picture(图片)】的数据库操作Service
* @createDate 2026-05-23 12:18:02
*/
public interface PictureService extends IService<Picture> {


    /**
     * 校验图片
     *
     * @param picture
     */
    void validPicture(Picture picture);



    /**
     * 上传图片
     * @param inputSource 图片输入源
     * @param pictureUploadRequest 图片上传请求
     * @param loginUser 登录用户
     * @return
     */
    PictureVO uploadPicture(Object inputSource, PictureUploadRequest pictureUploadRequest ,User loginUser);

    /**
     * 获取图片包装类（单条）
     *
     * @param picture
     * @param request
     * @return
     */
    PictureVO getPictureVO(Picture picture, HttpServletRequest request);

    /**
     * 获取图片包装类（分页）
     *
     * @param picturePage
     * @param request
     * @return
     */
    Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request);


    /**
     * 获取查询对象
     *
     * @param pictureQueryRequest 查询条件
     * @return
     */
    QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest);


    /**
     * 图片审核
     *
     * @param pictureReviewRequest 图片审核请求
     * @param loginUser 执行审核的用户
     */
    void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser);

    /**
     * 填充审核参数
     *
     * @param picture
     * @param loginUser
     */
    void fillReviewParams(Picture picture, User loginUser);

    /**
     * 批量抓取和创建图片
     *
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return 成功创建的图片数
     */
    Integer uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser);

    /**
     * 清理图片文件
     *
     * @param oldPicture
     */
    void clearPictureFile(Picture oldPicture);

    /**
     * 删除图片
     *
     * @param pictureId
     * @param loginUser
     */
    void deletePicture(long pictureId, User loginUser);
//
//    /**
//     * 编辑图片
//     *
//     * @param pictureEditRequest
//     * @param loginUser
//     */
//    void editPicture(PictureEditRequest pictureEditRequest, User loginUser);

    /**
     * 校验空间图片的权限
     *
     * @param loginUser
     * @param picture
     */
    void checkPictureAuth(User loginUser, Picture picture);

    /**
     * 编辑图片
     *
     * @param pictureEditRequest
     * @param loginUser
     */
    void editPicture(PictureEditRequest pictureEditRequest, User loginUser);

    /**
     * 根据颜色搜索图片
     *
     * @param spaceId
     * @param picColor
     * @param loginUser
     * @return
     */
    List<PictureVO> searchPictureByColor(Long spaceId, String picColor, User loginUser);

    /**
     * 批量编辑图片
     *
     * @param pictureEditByBatchRequest
     * @param loginUser
     */
    void editPictureByBatch(PictureEditByBatchRequest pictureEditByBatchRequest, User loginUser);

    /**
     * 创建扩图任务
     *
     * @param createPictureOutPaintingTaskRequest
     * @param loginUser
     */
    CreateOutPaintingTaskResponse createPictureOutPaintingTask(CreatePictureOutPaintingTaskRequest createPictureOutPaintingTaskRequest, User loginUser);

    /**
     * 创建 AI 图片编辑任务
     */
    CreateOutPaintingTaskResponse createPictureAiEditTask(
            CreatePictureAiEditTaskRequest createPictureAiEditTaskRequest,
            User loginUser
    );




}
