package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return  Result.fail("手机号格式错误");   //如果不符合，返回信息
        }
        //如果符合，生成验证码，保存到session当中
        String code = RandomUtil.randomNumbers(6);
        //session.setAttribute(phone,code);//把手机号作为key存放验证码
        //更新，将验证码放到redis当中
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code);
        stringRedisTemplate.expire(RedisConstants.LOGIN_CODE_KEY+phone,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);//设置验证码过期时间
        //模拟发送验证码
        log.debug("后端发送发送验证码到手机号{}：{}",phone,code);
        return  Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //校验手机号
        String phone =loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return  Result.fail("手机号格式错误");   //如果不符合，返回信息
        }
        //校验验证码

        //String cacheCode = (String) session.getAttribute(phone);
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+phone);
        if(cacheCode == null || !cacheCode.equals(loginForm.getCode())){
            return  Result.fail("验证码校验失败");
        }
        //查询用户
        User user = query().eq("phone", phone).one();
        //如果用户不存在注册
        if(user == null){
            User newUser = new User();
            newUser.setPhone(phone);
            newUser.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(6));//随机生成用户昵称
            user = newUser;
            save(user);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setFieldValueEditor(
                (name,value)->{
                    return value.toString();
                }
        ));
        //将bean对象转化为map对象
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //随机生成token返回给用户，用作之后的令牌
        String token = UUID.randomUUID().toString();
        //将用户保存在redis当中
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token,map);//使用map类型
        //设置token过期时间
        stringRedisTemplate.expire(token,30,TimeUnit.MINUTES);

        return Result.ok(token);
    }
}
