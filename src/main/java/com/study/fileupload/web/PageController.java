package com.study.fileupload.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 화면 2장: 업로드(/)와 차단 관리(/admin). 데이터는 전부 /api/** 로 가져온다. */
@Controller
public class PageController {

    @GetMapping("/")
    public String upload() {
        return "upload";
    }

    @GetMapping("/admin")
    public String admin() {
        return "admin";
    }
}
