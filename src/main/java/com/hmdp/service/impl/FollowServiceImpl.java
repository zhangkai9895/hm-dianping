package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    IUserService userService;
    /**
     * 关注用户
     *
     * @param followUserId 关注用户的id
     * @param isFollow     是否已关注
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (isFollow) {
            // 用户为关注，则关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            this.save(follow);
            String key = "follow:user:"+userId; //将关注放到redis 的set当中
            stringRedisTemplate.opsForSet().add(key,followUserId.toString());
        } else {
            this.remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            String key = "follow:user:"+userId; //将关注放到redis 的set当中
            stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
        }
        return Result.ok();
    }

    /**
     * 是否关注用户
     *
     * @param followUserId 关注用户的id
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id",userId).eq("follow_user_id",followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result getCommomFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key1 = "follow:user:"+userId;
        String key2 = "follow:user:"+followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null ||intersect.isEmpty()){
            return Result.fail("没有共同关注");
        }
        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> list = userService.listByIds(collect).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(list);
    }

}
