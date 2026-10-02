package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IBlogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 后台笔记内容管理控制器。
 *
 * <p>类级 {@link RequirePermission} 要求内容写权限；点赞关系由数据库外键随笔记删除。</p>
 */
@RestController
@RequestMapping("/admin/blogs")
@RequirePermission(PermissionCodes.CONTENT_WRITE)
public class AdminBlogController {

    /** 笔记领域服务，负责后台检索、删除和操作审计。 */
    @Resource
    private IBlogService blogService;

    /**
     * 分页检索后台笔记，关键词同时匹配标题和正文。
     *
     * @param current 页码，小于 1 或为空时按第 1 页处理
     * @param keyword 可选搜索词；空白时不过滤
     * @return 按主键倒序排列的笔记分页对象
     */
    @GetMapping
    public Result queryBlogs(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        return blogService.queryAdminBlogs(current, keyword);
    }

    /**
     * 删除指定笔记及其点赞关系，写入成功审计记录。
     *
     * @param id 笔记主键
     * @return 删除成功响应；记录不存在时返回 {@code BLOG_NOT_FOUND}
     */
    @DeleteMapping("/{id}")
    public Result deleteBlog(@PathVariable("id") Long id) {
        return blogService.deleteBlog(id);
    }
}
