package com.zys.backend.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 独立本机管理控制台页面入口。
 */
@Controller
public class LocalManageController {

    @GetMapping({"/manage", "/manage/"})
    public String managePage() {
        return "forward:/manage/index.html";
    }
}
