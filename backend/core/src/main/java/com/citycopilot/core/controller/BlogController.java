package com.citycopilot.core.controller;


import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Blog;
import com.citycopilot.core.service.IBlogService;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 用户笔记发布与浏览控制器。
 *
 * <p>提供发布、点赞、详情、热榜、关注流和用户主页等入口；涉及用户身份的业务规则
 * 由 {@link IBlogService} 与登录拦截链统一处理。</p>
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    /** 笔记领域服务，封装发布、点赞和聚合查询规则。 */
    @Resource
    private IBlogService blogService;

    /**
     * 发布当前登录用户的笔记。
     *
     * @param blog 标题、正文及关联店铺等笔记数据
     * @return 新笔记标识或服务层校验失败结果
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 切换当前用户对指定笔记的点赞状态。
     *
     * @param id 笔记主键
     * @return 点赞或取消点赞的处理结果
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    /**
     * 查询当前登录用户发布的笔记。
     *
     * @param current 页码，默认第 1 页
     * @return 当前页的笔记列表
     */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryBlogsByUser(UserHolder.getUser().getId(), current);
    }

    /**
     * 按热度分页查询公开笔记。
     *
     * @param current 页码，默认第 1 页
     * @return 已补充作者和点赞状态的热门笔记结果
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }
    /**
     * 查询单篇笔记详情。
     *
     * @param id 笔记主键
     * @return 笔记详情，或服务层返回的不存在结果
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id")Long id){
        return blogService.queryBlogById(id);
    }
    /**
     * 查询指定笔记最近的点赞用户。
     *
     * @param id 笔记主键
     * @return 点赞用户的安全视图列表
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id")Long id){
        return blogService.queryBlogLikes(id);
    }

    /**
     * 以时间戳游标滚动查询当前用户的关注流。
     *
     * @param lastId 上一页最小时间戳，首次请求传当前时间
     * @param offset 同一时间戳下已跳过的记录数
     * @return 下一段关注流及新的游标信息
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long lastId,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(lastId, offset);
    }

    /**
     * 分页查询指定用户发布的笔记。
     *
     * @param current 页码，默认第 1 页
     * @param id 目标用户主键
     * @return 当前页的笔记列表
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam(value = "current",defaultValue = "1")Integer current,
            @RequestParam("id")Long id){
        return blogService.queryBlogsByUser(id, current);
    }

}
