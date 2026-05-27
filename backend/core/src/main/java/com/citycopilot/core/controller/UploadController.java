package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.FileStorageService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("upload")
public class UploadController {

    @Resource
    private FileStorageService fileStorageService;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            return Result.ok(fileStorageService.storeBlogImage(image));
        } catch (IllegalArgumentException e) {
            return Result.fail("UPLOAD_INVALID", e.getMessage());
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        try {
            fileStorageService.deletePublicPath(filename);
            return Result.ok();
        } catch (IllegalArgumentException e) {
            return Result.fail("UPLOAD_INVALID", e.getMessage());
        }
    }
}
