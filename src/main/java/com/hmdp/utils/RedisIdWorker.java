package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局唯一id生成器
 */
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 946684800L;

    //序列号位数
    private static final int COUNT_BITS = 32;
    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /***
     * 不同的业务，key不同
     * @param keyPrefix
     * @return
     */
    public Long nextId(String keyPrefix){
        //时间戳
        Long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        Long timeStamp = now - BEGIN_TIMESTAMP;

        //redis自增长键
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long redisKey = stringRedisTemplate.opsForValue().increment("inc:"+keyPrefix+":" +date);
        return timeStamp << COUNT_BITS | redisKey;
    }


    //生成开始时间戳
    public static void main(String[] args) {
        LocalDateTime now = LocalDateTime.of(2000,1,1,0,0);
        System.out.println(now.toEpochSecond(ZoneOffset.UTC));
    }
}
