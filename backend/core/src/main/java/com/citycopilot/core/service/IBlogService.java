package com.citycopilot.core.service;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Blog;

import java.time.LocalDateTime;

/**
 * 博客领域服务，负责详情、热榜、点赞、发布和关注流查询。
 */
public interface IBlogService {

    /**
     * 查询博客详情，并补充作者与当前用户点赞状态。
     *
     * @param id 博客主键
     * @return 详情结果；博客不存在时返回失败结果
     */
    Result queryBlogById(Long id);

    /**
     * 按热度分页查询博客，并补充作者信息。
     *
     * @param current 从 1 开始的页码
     * @return 当前页博客列表
     */
    Result queryHotBlog(Integer current);

    /** 按关键词分页检索后台笔记，保留管理列表的分页元数据。 */
    Result queryAdminBlogs(Integer current, String keyword);

    /** 删除笔记，并在删除完成后记录原始内容快照的管理审计。 */
    Result deleteBlog(Long id);

    /** 后台概览中的笔记总数。 */
    Long countBlogs();

    /** 后台概览中不早于指定时间发布的笔记数。 */
    Long countBlogsCreatedSince(LocalDateTime since);

    /**
     * 切换当前用户对指定博客的点赞状态。
     *
     * @param id 博客主键
     * @return 操作结果；关系与计数在同一数据库事务中更新
     */
    Result likeBlog(Long id);

    /**
     * 查询最早点赞的前五位用户。
     *
     * @param id 博客主键
     * @return 按点赞时间升序排列的用户摘要
     */
    Result queryBlogLikes(Long id);

    /**
     * 发布博客，关注流按数据库中的当前关注关系查询。
     *
     * @param blog 待保存的博客内容，作者由当前登录用户确定
     * @return 新博客主键
     */
    Result saveBlog(Blog blog);

    /**
     * 使用滚动游标读取当前用户的关注动态。
     *
     * @param lastId 上一页最小时间戳；首次查询传当前时间戳
     * @param offset 同一时间戳下已跳过的记录数
     * @return 博客列表及下一页游标
     */
    Result queryBlogOfFollow(Long lastId, Integer offset);
}
