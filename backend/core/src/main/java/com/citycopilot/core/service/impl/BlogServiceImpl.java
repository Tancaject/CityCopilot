package com.citycopilot.core.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.citycopilot.core.dto.BlogView;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ScrollResult;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.Blog;
import com.citycopilot.core.mapper.BlogMapper;
import com.citycopilot.core.service.IBlogService;
import com.citycopilot.core.service.IUserService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.utils.SystemConstants;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** MySQL owns community relationships; counts and feed are derived from those facts. */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    private final IUserService userService;
    private final OperationLogService operationLogService;

    public BlogServiceImpl(IUserService userService, OperationLogService operationLogService) {
        this.userService = userService;
        this.operationLogService = operationLogService;
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) return Result.fail("BLOG_NOT_FOUND", "查询不到笔记");
        enrich(List.of(blog));
        return Result.ok(toView(blog));
    }

    @Override
    public Result queryHotBlog(Integer current) {
        if (current == null || current < 1) return Result.fail("INVALID_PAGE", "页码必须大于零");
        // 只返回记录不返回总数，关闭分页插件每次附带的 COUNT(*) 全表计数。
        List<Blog> blogs = query().orderByDesc("liked", "id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE, false)).getRecords();
        enrich(blogs);
        return Result.ok(blogs.stream().map(BlogServiceImpl::toView).toList());
    }

    @Override
    public Result queryAdminBlogs(Integer current, String keyword) {
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<Blog> page = query()
                .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                        .like("title", keyword)
                        .or()
                        .like("content", keyword))
                .orderByDesc("id")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.convert(BlogServiceImpl::toView));
    }

    @Override
    public Result deleteBlog(Long id) {
        Blog before = getById(id);
        if (before == null) {
            return Result.fail("BLOG_NOT_FOUND", "笔记不存在");
        }
        removeById(id);
        // 审计仍在删除完成后独立写入，不把两者扩展为同一事务。
        operationLogService.record("content", "blog_delete", "blog", id, String.valueOf(id), before, null, true, null);
        return Result.ok();
    }

    @Override
    public Long countBlogs() {
        return query().count();
    }

    @Override
    public Long countBlogsCreatedSince(LocalDateTime since) {
        return query().ge("create_time", since).count();
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        // The note row serializes toggles and counter maintenance in the same transaction.
        // ponytail: one row lock per note; split counter aggregation only after measured hot-note contention.
        if (baseMapper.lockNote(id) == null) return Result.fail("BLOG_NOT_FOUND", "笔记不存在");
        boolean removed = baseMapper.deleteLike(id, userId) > 0;
        if (!removed) {
            baseMapper.insertLike(id, userId, System.currentTimeMillis());
        }
        baseMapper.adjustLikeCount(id, removed ? -1 : 1);
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        List<Long> ids = baseMapper.firstLikers(id);
        if (ids.isEmpty()) return Result.ok(List.of());
        Map<Long, UserDTO> users = userService.findUserProfiles(ids).stream()
                .collect(Collectors.toMap(UserDTO::getId, Function.identity()));
        return Result.ok(ids.stream().map(users::get).filter(Objects::nonNull).toList());
    }

    @Override
    public Result saveBlog(Blog blog) {
        blog.setId(null);
        blog.setUserId(UserHolder.getUser().getId());
        blog.setLiked(0);
        blog.setComments(0);
        blog.setCreateTime(null);
        blog.setUpdateTime(null);
        return save(blog) ? Result.ok(blog.getId()) : Result.fail("BLOG_CREATE_FAILED", "新增笔记失败");
    }

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        if (lastId == null || lastId < 0 || offset == null || offset < 0) {
            return Result.fail("INVALID_CURSOR", "关注动态游标无效，请刷新后重试");
        }
        List<BlogMapper.FeedEntry> entries = baseMapper.followFeed(UserHolder.getUser().getId(), lastId, offset);
        if (entries.isEmpty()) return Result.ok();
        List<Long> ids = entries.stream().map(BlogMapper.FeedEntry::id).toList();
        Map<Long, Blog> byId = listByIds(ids).stream().collect(Collectors.toMap(Blog::getId, Function.identity()));
        List<Blog> blogs = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        enrich(blogs);
        long minTime = entries.get(entries.size() - 1).time();
        int sameTime = (int) entries.stream().filter(entry -> entry.time() == minTime).count();
        ScrollResult result = new ScrollResult();
        result.setList(blogs.stream().map(BlogServiceImpl::toView).toList());
        result.setMinTime(minTime);
        result.setOffset(sameTime + (minTime == lastId ? offset : 0));
        return Result.ok(result);
    }

    private static BlogView toView(Blog blog) {
        return new BlogView(blog.getId(), blog.getShopId(), blog.getUserId(),
                blog.getIcon(), blog.getName(), blog.getIsLike(), blog.getTitle(),
                blog.getImages(), blog.getContent(), blog.getLiked(), blog.getComments(),
                blog.getCreateTime(), blog.getUpdateTime());
    }

    private void enrich(List<Blog> blogs) {
        if (blogs.isEmpty()) return;
        Map<Long, UserDTO> authors = userService.findUserProfiles(blogs.stream().map(Blog::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserDTO::getId, Function.identity()));
        UserDTO viewer = UserHolder.getUser();
        Set<Long> liked = viewer == null ? Set.of() : new HashSet<>(baseMapper.likedNotes(viewer.getId(),
                blogs.stream().map(Blog::getId).toList()));
        for (Blog blog : blogs) {
            UserDTO author = authors.get(blog.getUserId());
            if (author != null) {
                blog.setName(author.getNickName());
                blog.setIcon(author.getIcon());
            }
            blog.setIsLike(liked.contains(blog.getId()));
        }
    }
}
