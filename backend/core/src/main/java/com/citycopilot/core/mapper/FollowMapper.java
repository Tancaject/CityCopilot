package com.citycopilot.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citycopilot.core.entity.Follow;
import org.apache.ibatis.annotations.*;

/** 通过 MyBatis-Plus 持久化用户关注关系。 */
public interface FollowMapper extends BaseMapper<Follow> {
    @Insert("INSERT INTO user_follow(user_id,follow_user_id) VALUES(#{userId},#{targetId}) " +
            "ON DUPLICATE KEY UPDATE id=id")
    int ensureFollow(@Param("userId") Long userId, @Param("targetId") Long targetId);

}
