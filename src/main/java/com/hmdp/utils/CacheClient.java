package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    //构造器注入
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //封装自动序列化key并插入到数据库当中
    public void  set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /***
     *
     * @param keyPrefix 前缀
     * @param id 查询id
     * @param type 类型
     * @param dbFallback 传入函数
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    //从缓存中获取数据，同时解决缓存穿透问题
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(keyPrefix+id);
        if(!StrUtil.isBlank(json)){
          //  log.info("缓存命中！查询id为：{}内容已经返回",id);
            return JSONUtil.toBean(json,type);
            //缓存命中，返回缓存中内容
        }
        //解决缓存穿透问题
        if(Objects.nonNull(json)){
          //  log.info("缓存未命中");
            //说明缓存的是空字符串
            return null;
        }
        //如果缓存中没有
        //调用回调函数去数据库中查询
        R r = dbFallback.apply(id);
        if (r ==null){
           // log.info("数据库中id为{}内容不存在,插入空值",id);
            stringRedisTemplate.opsForValue().set(keyPrefix+id,"",2,TimeUnit.MINUTES);//空值过期时间
            //缓存中插入空对象解决缓存穿透问题
            return null;
        }
        set(keyPrefix+id,r,time,unit);
        return r;
    }


}
