package com.citycopilot.core.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.citycopilot.core.entity.BlogComments;
import com.citycopilot.core.mapper.BlogCommentsMapper;
import com.citycopilot.core.service.IBlogCommentsService;
import org.springframework.stereotype.Service;

/**
 * 博客评论服务的 MyBatis-Plus 实现。
 *
 * <p>{@link BlogCommentsMapper} 的持久化能力保留在实现内部；领域接口尚未定义公开操作。</p>
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
