package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.FileStorageService;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 博客图片上传和删除接口；可通过配置整体关闭。 */
@RestController
@RequestMapping("upload")
@ConditionalOnProperty(
        prefix = "citycopilot.upload",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class UploadController {

    /** 校验文件类型/大小并限制路径穿越的本地存储服务。 */
    @Resource
    private FileStorageService fileStorageService;

    /**
     * @param image multipart 图片文件
     * @return 可公开访问的图片路径，或 UPLOAD_INVALID 失败
     */
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            return Result.ok(fileStorageService.storeBlogImage(image));
        } catch (IllegalArgumentException e) {
            return Result.fail("UPLOAD_INVALID", e.getMessage());
        }
    }
}
