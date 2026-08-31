package com.zys.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zys.backend.model.dto.user.UserQueryRequest;
import com.zys.backend.model.entity.User;
import com.zys.backend.model.vo.LoginUserVO;
import com.zys.backend.model.vo.UserVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * @author 10415
 * @description 针对表【user(用户)】的数据库操作Service
 * @createDate 2026-05-19 19:32:35
 */
public interface UserService extends IService<User> {
    /**
     * 用户注册
     *
     * @param userAccount   用户账号
     * @param userPassword  用户密码
     * @param checkPassword 确认密码
     * @return 注册结果
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户登录
     *
     * @param userAccount  用户账号
     * @param userPassword 用户密码
     * @param request      HttpServletRequest 请求对象
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request HttpServletRequest 请求对象
     * @return 登录用户
     */
    User getLoginUser(HttpServletRequest request);


    /**
     * 用户注销
     *
     * @param request HttpServletRequest 请求对象
     */
    boolean userLogout(HttpServletRequest request);


    /* -------------------------------------------------------------------------------------- */
    /**
     * User 转换为 LoginUserVO
     *
     * @param user 用户实体
     * @return LoginUserVO 视图对象
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * User 转换为 UserVO,获取脱敏后的用户信息
     *
     * @param user 用户实体
     * @return UserVO 视图对象
     */
    UserVO getUserVO(User user);


    /**
     * User 转换为 UserVO,获取脱敏后的用户信息列表
     *
     * @param userList 用户实体列表
     * @return 用户VO列表
     */
    List<UserVO> getUserVOList(List<User> userList);


    /**
     * 根据查询请求构建查询条件
     *
     * @param userQueryRequest 查询请求
     * @return 查询条件包装器
     */
    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);

    /**
     * 加密密码
     *
     * @param password 密码
     * @return 加密后的密码
     */
    String getEncryptPassword(String password);

    /**
     * 判断是否为管理员
     *
     * @param user 用户实体
     * @return 是否为管理员
     */
    boolean isAdmin(User user);


}
