package com.citycopilot.core.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储端口，隔离业务代码与具体磁盘或对象存储实现。
 *
 * <p>当前由博客图片上传流程调用，业务层只保存可公开访问的路径。</p>
 */
public interface FileStorageService {

    /**
     * 校验并保存一张博客图片。
     *
     * @param image 客户端上传的图片文件
     * @return 可写入博客内容的公开访问路径
     */
    String storeBlogImage(MultipartFile image);
}
