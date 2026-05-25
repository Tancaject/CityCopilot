package com.citycopilot.core.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Blog;
import com.citycopilot.core.security.PermissionCodes;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    @AgentTool(name = "get_blog_detail", description = "Get blog detail by blog id")
    Result queryBlogById(Long id);

    @AgentTool(name = "list_hot_blogs", description = "List hot blogs by page")
    Result queryHotBlog(Integer current);

    @AgentTool(
            name = "like_blog",
            description = "Like or unlike a blog for current user",
            requiredPermissions = {PermissionCodes.CONTENT_WRITE},
            idempotent = false
    )
    Result likeBlog(Long id);

    @AgentTool(name = "list_blog_likes", description = "List users who liked a blog")
    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    @AgentTool(name = "get_follow_feed", description = "Get current user's follow feed")
    Result queryBlogOfFollow(Long lastId, Integer offset);
}
