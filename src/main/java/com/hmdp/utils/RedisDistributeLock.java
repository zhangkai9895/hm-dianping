package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/***
 * 使用redis实现分布式锁
 */
public class RedisDistributeLock {


    private StringRedisTemplate stringRedisTemplate;

    public RedisDistributeLock(StringRedisTemplate stringRedisTemplate, String lockName) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockName = lockName;
    }

    private String lockName;//锁的名字

    private String lockNamePrefix = "RedisDisLock:";

    /**
     * 尝试获取锁，获取不到则返回false,不进行阻塞等待,设置过期时间，watchdog机制
     * @return
     */
    public boolean tryLock(long expireTime){
        String threadID = Thread.currentThread().getId()+"";
        Boolean set = stringRedisTemplate.opsForValue().setIfAbsent(lockNamePrefix + lockName, threadID, expireTime, TimeUnit.SECONDS);
        return set;
    }

    /**
     * 释放分布式锁
     */
    public boolean unlock(){
        return  stringRedisTemplate.delete(lockNamePrefix+lockName);
    }
}
