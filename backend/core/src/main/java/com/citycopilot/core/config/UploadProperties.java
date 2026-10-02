package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 绑定 {@code citycopilot.upload}，限制图片存储目录、公开路径、大小和文件类型。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.upload")
public class UploadProperties {
    /** 图片落盘根目录。 */
    private String rootDir = "uploads";
    /** MVC 暴露上传文件时使用的 URL 前缀。 */
    private String publicPrefix = "/uploads";
    /** 单张图片最大字节数。 */
    private long maxImageSizeBytes = 5 * 1024 * 1024;
    /** 允许的文件扩展名白名单。 */
    private List<String> allowedImageExtensions = new ArrayList<>(List.of("jpg", "jpeg", "png", "webp", "gif"));
    /** 允许的 HTTP Content-Type 白名单。 */
    private List<String> allowedImageContentTypes = new ArrayList<>(List.of("image/jpeg", "image/png", "image/webp", "image/gif"));
}
