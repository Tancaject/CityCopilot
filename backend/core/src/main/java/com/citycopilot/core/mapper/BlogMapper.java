package com.citycopilot.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citycopilot.core.entity.Blog;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 通过 MyBatis-Plus 提供探店笔记表的基础增删改查。 */
public interface BlogMapper extends BaseMapper<Blog> {
    @Select("SELECT id FROM note WHERE id=#{id} FOR UPDATE")
    Long lockNote(@Param("id") Long id);

    @Delete("DELETE FROM note_like WHERE note_id=#{noteId} AND user_id=#{userId}")
    int deleteLike(@Param("noteId") Long noteId, @Param("userId") Long userId);

    @Insert("INSERT INTO note_like(note_id,user_id,liked_at) VALUES(#{noteId},#{userId},#{time})")
    int insertLike(@Param("noteId") Long noteId, @Param("userId") Long userId, @Param("time") long time);

    @Update("UPDATE note SET liked=liked+#{delta} WHERE id=#{id}")
    int adjustLikeCount(@Param("id") Long id, @Param("delta") int delta);

    @Select("<script>SELECT note_id FROM note_like WHERE user_id=#{userId} AND note_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> likedNotes(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    @Select("SELECT user_id FROM note_like WHERE note_id=#{id} ORDER BY liked_at,user_id LIMIT 5")
    List<Long> firstLikers(@Param("id") Long id);

    record FeedEntry(Long id, long time) {}

    @Select("SELECT n.id,CAST(UNIX_TIMESTAMP(n.create_time)*1000 AS UNSIGNED) AS time " +
            "FROM user_follow f JOIN note n ON n.user_id=f.follow_user_id " +
            "WHERE f.user_id=#{userId} AND n.create_time<=FROM_UNIXTIME(#{lastId}/1000.0) " +
            "ORDER BY n.create_time DESC,n.id DESC LIMIT 3 OFFSET #{offset}")
    List<FeedEntry> followFeed(@Param("userId") Long userId, @Param("lastId") long lastId, @Param("offset") int offset);

}
