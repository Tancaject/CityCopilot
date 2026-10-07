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

/**
 * 本地磁盘文件存储实现，负责博客图片的安全校验、分目录落盘和删除。
 *
 * <p>控制层只接触公开路径；本类把公开 URL 前缀转换为配置目录下的规范化绝对路径。</p>
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    /** 上传目录、公开前缀、大小上限及允许类型配置。 */
    @Resource
    private UploadProperties uploadProperties;

    @Override
    public String storeBlogImage(MultipartFile image) {
        validateImage(image);
        String fileName = createRelativeBlogPath(image.getOriginalFilename());
        Path root = uploadRoot();
        Path target = root.resolve(stripLeadingSlash(fileName)).normalize();
        // 规范化后必须仍位于上传根目录，防止文件名通过 ../ 越权写盘。
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

    /**
     * 从大小、扩展名、MIME 类型和文件头四个维度校验上传内容。
     *
     * @param image 待校验上传文件
     * @throws IllegalArgumentException 文件为空、超限或类型不可信时抛出
     */
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

    /**
     * 根据扩展名验证常见图片格式的魔数，避免仅伪造后缀或 MIME 类型。
     *
     * @param image 待读取文件头的图片
     * @param extension 已标准化为小写的扩展名
     * @return 文件头是否与声明格式一致
     */
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

    /**
     * 为图片生成散列分片目录和随机文件名，避免单目录积累过多文件。
     *
     * @param originalFilename 原始文件名，仅用于确定扩展名
     * @return 以 {@code /blogs/} 开头的相对路径
     */
    private String createRelativeBlogPath(String originalFilename) {
        String suffix = imageExtension(originalFilename);
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    /**
     * 提取并标准化文件扩展名。
     *
     * @param originalFilename 客户端原始文件名
     * @return 小写扩展名，不含点号
     */
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

    /** @return 规范化且不含符号链接解析的上传根目录绝对路径 */
    private Path uploadRoot() {
        return Path.of(uploadProperties.getRootDir()).toAbsolutePath().normalize();
    }
}
