package com.company.platform;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 独立平台网页入口；hash 路由不需要把未知 API 回退成 HTML。 */
@Controller
public class PlatformWeb {
    /** 根地址只转发本批构建入口，不复用旧档案 index.html。 */
    @GetMapping("/") public String home() { return "forward:/platform.html"; }
}
