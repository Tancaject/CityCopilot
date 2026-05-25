package com.citycopilot.core.service;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
    String storeBlogImage(MultipartFile image);

    boolean deletePublicPath(String publicPath);
}
