package com.citycopilot.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citycopilot.core.entity.Follow;
import com.citycopilot.core.dto.UserDTO;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 通过 MyBatis-Plus 持久化用户关注关系。 */
public interface FollowMapper extends BaseMapper<Follow> {
    @Insert("INSERT INTO user_follow(user_id,follow_user_id) VALUES(#{userId},#{targetId}) " +
            "ON DUPLICATE KEY UPDATE id=id")
    int ensureFollow(@Param("userId") Long userId, @Param("targetId") Long targetId);

    @Select("SELECT u.id,u.nick_name,u.icon FROM user_follow a " +
            "JOIN user_follow b ON b.follow_user_id=a.follow_user_id " +
            "JOIN user_account u ON u.id=a.follow_user_id " +
            "WHERE a.user_id=#{userId} AND b.user_id=#{otherId} ORDER BY u.id")
    List<UserDTO> commonFollows(@Param("userId") Long userId, @Param("otherId") Long otherId);

}
