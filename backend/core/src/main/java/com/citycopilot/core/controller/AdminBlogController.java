package com.citycopilot.core.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Blog;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IBlogService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.citycopilot.core.utils.RedisConstants.BLOG_LIKED_KEY;

@RestController
@RequestMapping("/admin/blogs")
@RequirePermission(PermissionCodes.CONTENT_WRITE)
public class AdminBlogController {

    @Resource
    private IBlogService blogService;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping
    public Result queryBlogs(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<Blog> page = blogService.query()
                .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                        .like("title", keyword)
                        .or()
                        .like("content", keyword))
                .orderByDesc("id")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page);
    }

    @DeleteMapping("/{id}")
    public Result deleteBlog(@PathVariable("id") Long id) {
        Blog before = blogService.getById(id);
        if (before == null) {
            return Result.fail("BLOG_NOT_FOUND", "笔记不存在");
        }
        blogService.removeById(id);
        stringRedisTemplate.delete(BLOG_LIKED_KEY + id);
        operationLogService.record("content", "blog_delete", "blog", id, String.valueOf(id), before, null, true, null);
        return Result.ok();
    }
}
