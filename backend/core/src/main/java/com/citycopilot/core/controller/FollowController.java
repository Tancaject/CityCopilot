package com.citycopilot.core.controller;


import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.IFollowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id")Long followUserId,@PathVariable("isFollow")Boolean isFollow){
        return followService.follow(followUserId,isFollow);
    }
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id")Long followUserId){
        return followService.isFollow(followUserId);
    }
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id")Long followUserId){
        return followService.followCommons(followUserId);
    }
}
