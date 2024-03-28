package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Autowired
    IFollowService followService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 点赞博客，每人只能点赞一次，使用redis的set结构存储用户是否点赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //使用redis缓存是否已经点过赞
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY+ id; //存入到redis当中
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            boolean isSucess = this.update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSucess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else{
            boolean isSucess = this.update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSucess) {
                // 数据更新成功，更新缓存 srem key value
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询某篇笔记是否被当前用户点赞
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (Objects.isNull(user)){
            // 当前用户未登录，无需查询点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(Objects.nonNull(score));
    }


    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 根据id降序排序 select * from tb_user where id in(1,5) order by field(id, 1, 5)
        List<UserDTO> userDTOList = userService.query().in("id",ids)
                .last("order by field (id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        save(blog);
        //将文章推以feed流推送到粉丝箱里
        //1。找到所有关注者
        List<Follow> followId = followService.query().eq("follow_user_id", user.getId()).list();
        for(Follow follow:followId){
            Long id = follow.getUserId();
            String key = "feed:"+id;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result getFollowBlog(Long lastId, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        //找到当前用户的收信箱
        String key = "feed:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        if(typedTuples == null ||typedTuples.isEmpty()){
            return Result.fail("没有新推文");
        }
        List<Long> blogIds = new ArrayList<>();
        Long lastScore = 0L;
        Integer off = 1;
        for(ZSetOperations.TypedTuple<String> blogTuple: typedTuples){
            String blogIdStr = blogTuple.getValue();
            blogIds.add(Long.valueOf(blogIdStr));
            Long score = blogTuple.getScore().longValue();
            if(score == lastScore){
                off++;
            }else{
                lastScore = score;
                off = 1;
            }
        }
        List<Blog> blogs = new ArrayList<>();
        for(Long id:blogIds){
            Blog blog = getById(id);
            Long blogUserId = blog.getUserId();
            User user = userService.getById(blogUserId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
            blogs.add(blog);
        }
        ScrollResult ret = new ScrollResult();
        ret.setList(blogs);
        ret.setMinTime(lastScore);
        ret.setOffset(off);
        return Result.ok(ret);
    }

}
