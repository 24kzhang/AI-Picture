package com.zys.backend.controller;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zys.backend.annotation.AuthCheck;
import com.zys.backend.common.BaseResponse;
import com.zys.backend.common.DeleteRequest;
import com.zys.backend.common.ResultUtils;
import com.zys.backend.constant.UserConstant;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import com.zys.backend.model.dto.user.*;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.vo.LoginUserVO;
import com.zys.backend.model.vo.UserVO;
import com.zys.backend.service.UserService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController // 自动把返回值转换为 json 格式
@RequestMapping("/user")
public class UserController {
    @GetMapping("/health")
    public BaseResponse<String> health() {
        return ResultUtils.success("OK!");
    }

    @Resource
    private UserService userService;

    /**
     * 用户注册
     *
     * @param userRegisterRequest 用户注册请求
     * @return 注册结果
     */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();

        long result = userService.userRegister(userAccount, userPassword, checkPassword);

        return ResultUtils.success(result);
    }


    /**
     * 用户注册
     *
     * @param userLoginRequest 用户登录请求
     * @return 登录结果
     */
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);

        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();

        LoginUserVO loginUserVO = userService.userLogin(userAccount, userPassword, request);

        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户信息
     *
     * @param request HTTP 请求对象
     * @return 当前登录用户信息视图
     */
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 用户注销
     *
     * @param request HTTP 请求对象
     * @return 注销结果
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        boolean result = userService.userLogout(request);
        return ResultUtils.success(result);
    }


    /**
     * 管理员添加用户
     *
     * @param userAddRequest 用户添加请求
     * @return 添加结果
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> userAdd(@RequestBody UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        User user = new User();
        BeanUtil.copyProperties(userAddRequest, user);
        // 默认密码
        final String DEFAULT_PASSWORD = "123456";
        String encryptPassword = userService.getEncryptPassword(DEFAULT_PASSWORD);
        user.setUserPassword(encryptPassword);
        // 插入数据库
        boolean result = userService.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

        return ResultUtils.success(user.getId());
    }

    /**
     * 根据 id 获取用户信息（管理员权限）
     *
     * @param id 用户ID
     * @return 用户信息
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(user);
    }

    /**
     * 根据 id 获取用户信息视图
     *
     * @param id 用户ID
     * @return 用户信息视图
     */
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserByIdVO(long id){
        BaseResponse<User> response = getUserById(id);
        User user = response.getData();
        return ResultUtils.success(userService.getUserVO(user));
    }


    /**
     * 删除用户（管理员权限）
     *
     * @param deleteRequest 删除用户请求
     * @return 删除结果
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "删除用户ID错误");
        }
        boolean result = userService.removeById(deleteRequest.getId());
        return ResultUtils.success(result);
    }


    /**
     * 更新用户信息（管理员权限）
     *
     * @param userUpdateRequest 用户更新请求
     * @return 更新结果
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {
        if (userUpdateRequest == null || userUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "更新用户ID错误");
        }
        User user = new User();
        BeanUtil.copyProperties(userUpdateRequest, user);
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 分页获取用户封装列表（管理员权限）
     *
     * @param userQueryRequest 用户查询请求
     * @return 用户封装列表
     */
    @PostMapping("list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {{
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);

        long current = userQueryRequest.getCurrent();
        long pageSize = userQueryRequest.getPageSize();

        Page<User> userPage = userService.page(new Page<>(current, pageSize), userService.getQueryWrapper(userQueryRequest));

        Page<UserVO> userVOPage = new Page<>(current, pageSize, userPage.getTotal());
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);

        return ResultUtils.success(userVOPage);

    }}


}
