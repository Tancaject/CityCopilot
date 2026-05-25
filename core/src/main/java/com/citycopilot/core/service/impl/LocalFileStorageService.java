package com.citycopilot.core.service.impl;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.UploadProperties;
import com.citycopilot.core.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    @Resource
    private UploadProperties uploadProperties;

    @Override
    public String storeBlogImage(MultipartFile image) {
        validateImage(image);
        String fileName = createRelativeBlogPath(image.getOriginalFilename());
        Path root = uploadRoot();
        Path target = root.resolve(stripLeadingSlash(fileName)).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("错误的文件名称");
        }
        try {
            Files.createDirectories(target.getParent());
            image.transferTo(target.toFile());
            log.debug("文件上传成功，{}", target);
            return publicPath(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @Override
    public boolean deletePublicPath(String publicPath) {
        String relativePath = normalizeInputPath(publicPath);
        if (StrUtil.isBlank(relativePath)) {
            throw new IllegalArgumentException("错误的文件名称");
        }
        Path root = uploadRoot();
        Path target = root.resolve(stripLeadingSlash(relativePath)).normalize();
        if (!target.startsWith(root) || Files.isDirectory(target)) {
            throw new IllegalArgumentException("错误的文件名称");
        }
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new RuntimeException("文件删除失败", e);
        }
    }

    private void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片");
        }
        if (image.getSize() > uploadProperties.getMaxImageSizeBytes()) {
            throw new IllegalArgumentException("单张图片不能超过 " + uploadProperties.getMaxImageSizeBytes() / 1024 / 1024 + "MB");
        }
        String extension = imageExtension(image.getOriginalFilename());
        Set<String> allowedExtensions = uploadProperties.getAllowedImageExtensions().stream()
                .map(item -> item.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException("不支持的图片格式");
        }
        String contentType = StrUtil.blankToDefault(image.getContentType(), "").toLowerCase(Locale.ROOT);
        Set<String> allowedContentTypes = uploadProperties.getAllowedImageContentTypes().stream()
                .map(item -> item.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!allowedContentTypes.contains(contentType)) {
            throw new IllegalArgumentException("不支持的图片类型");
        }
        if (!matchesImageSignature(image, extension)) {
            throw new IllegalArgumentException("图片内容与文件类型不匹配");
        }
    }

    private boolean matchesImageSignature(MultipartFile image, String extension) {
        try (InputStream stream = new BufferedInputStream(image.getInputStream())) {
            byte[] header = stream.readNBytes(12);
            return switch (extension) {
                case "jpg", "jpeg" -> header.length >= 3
                        && unsigned(header[0]) == 0xFF
                        && unsigned(header[1]) == 0xD8
                        && unsigned(header[2]) == 0xFF;
                case "png" -> header.length >= 8
                        && unsigned(header[0]) == 0x89
                        && header[1] == 0x50
                        && header[2] == 0x4E
                        && header[3] == 0x47
                        && header[4] == 0x0D
                        && header[5] == 0x0A
                        && header[6] == 0x1A
                        && header[7] == 0x0A;
                case "gif" -> header.length >= 6
                        && header[0] == 0x47
                        && header[1] == 0x49
                        && header[2] == 0x46
                        && header[3] == 0x38
                        && (header[4] == 0x37 || header[4] == 0x39)
                        && header[5] == 0x61;
                case "webp" -> header.length >= 12
                        && header[0] == 0x52
                        && header[1] == 0x49
                        && header[2] == 0x46
                        && header[3] == 0x46
                        && header[8] == 0x57
                        && header[9] == 0x45
                        && header[10] == 0x42
                        && header[11] == 0x50;
                default -> false;
            };
        } catch (IOException e) {
            throw new RuntimeException("图片读取失败", e);
        }
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }

    private String createRelativeBlogPath(String originalFilename) {
        String suffix = imageExtension(originalFilename);
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    private String imageExtension(String originalFilename) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        if (StrUtil.isBlank(suffix) || suffix.contains("/") || suffix.contains("\\")) {
            throw new IllegalArgumentException("图片文件名无效");
        }
        return suffix.toLowerCase(Locale.ROOT);
    }

    private String publicPath(String relativePath) {
        return normalizePrefix(uploadProperties.getPublicPrefix()) + stripLeadingSlash(relativePath);
    }

    private String normalizeInputPath(String path) {
        String normalized = StrUtil.blankToDefault(path, "").replace('\\', '/');
        String publicPrefix = normalizePrefix(uploadProperties.getPublicPrefix());
        if (normalized.startsWith(publicPrefix)) {
            normalized = normalized.substring(publicPrefix.length() - 1);
        }
        if (normalized.startsWith("/imgs/")) {
            normalized = normalized.substring("/imgs".length());
        }
        return normalized;
    }

    private String normalizePrefix(String prefix) {
        String normalized = StrUtil.blankToDefault(prefix, "/uploads").replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private Path uploadRoot() {
        return Path.of(uploadProperties.getRootDir()).toAbsolutePath().normalize();
    }
}
