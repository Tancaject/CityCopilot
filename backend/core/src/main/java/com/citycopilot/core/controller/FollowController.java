package com.citycopilot.core.controller;


import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.IFollowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/** 当前用户关注、取关及关系查询的 HTTP 接口。 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    /** 维护持久关注关系的业务服务。 */
    @Resource
    IFollowService followService;

    /**
     * @param followUserId 被操作用户主键
     * @param isFollow true 为关注，false 为取关
     * @return 操作结果
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id")Long followUserId,@PathVariable("isFollow")Boolean isFollow){
        return followService.follow(followUserId,isFollow);
    }
    /**
     * @param followUserId 目标用户主键
     * @return 当前用户是否已关注目标
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id")Long followUserId){
        return followService.isFollow(followUserId);
    }
}
