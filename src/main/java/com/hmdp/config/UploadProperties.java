package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.upload")
public class UploadProperties {
    private String rootDir = "uploads";
    private String publicPrefix = "/uploads";
    private long maxImageSizeBytes = 5 * 1024 * 1024;
    private List<String> allowedImageExtensions = new ArrayList<>(List.of("jpg", "jpeg", "png", "webp", "gif"));
    private List<String> allowedImageContentTypes = new ArrayList<>(List.of("image/jpeg", "image/png", "image/webp", "image/gif"));
}
