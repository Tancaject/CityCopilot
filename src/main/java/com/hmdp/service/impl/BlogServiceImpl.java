package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.hmdp.dto.ScrollResult;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog=getById(id);
        if (blog==null){
            return Result.fail("BLOG_NOT_FOUND", "查询不到笔记");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user==null)return;//如果用户未登录直接返回，不需要查询是否点赞
        Long userId= user.getId();
        //判断是否已经点过赞了
        Double score=stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY+blog.getId(),userId.toString());
        blog.setIsLike(score!=null );
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //获得当前用户id
        Long userId= UserHolder.getUser().getId();
        //判断是否已经点过赞了
        Double score=stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY+id,userId.toString());
        //没点过赞，则点赞+1
        if (score==null){
            //更新数据库
            boolean success = update().setSql("liked = liked +1").eq("id", id).update();
             if (success){//更新数据库成功则写入redis的sortedSet
                 stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY+id,userId.toString(),System.currentTimeMillis());
             }
        }else {//如果已点赞则取消点赞
            boolean success = update().setSql("liked = liked -1").eq("id", id).update();
            if (success){//更新数据库成功则从redis中移除用户点赞信息
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY+id,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
         //查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5==null||top5.isEmpty())return Result.ok(Collections.emptyList());
        //解析出用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据id查用户
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        //返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //获得登录用户信息
        UserDTO userDTO=UserHolder.getUser();
        blog.setUserId(userDTO.getId());
        //保存探店笔记
        boolean isSuccess=save(blog);
        if (!isSuccess){
            return Result.fail("BLOG_CREATE_FAILED", "新增笔记失败！");
        }
        //查询笔记作者所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", userDTO.getId()).list();
        //推送笔记给粉丝
        for (Follow follow:follows){
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key=FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        // 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 从 Redis ZSet 中滚动分页查询：ZREVRANGEBYSCORE feed:{userId} lastId 0 WITHSCORES LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, lastId, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 解析数据：blogId、score（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1; // 统计与最小时间戳相同的元素个数（用于下次分页偏移）
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 根据 id 查询 blog（保持顺序）
        String idStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        blogs.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        // 封装返回
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(os);
        return Result.ok(result);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
