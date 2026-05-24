package com.hmdp.service;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
    String storeBlogImage(MultipartFile image);

    boolean deletePublicPath(String publicPath);
}
