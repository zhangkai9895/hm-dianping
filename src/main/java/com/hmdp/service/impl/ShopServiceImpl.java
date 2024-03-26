package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    CacheClient cacheClient;


    private static  final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);//重建线程池
    /***
     * 根据id查询店铺
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //根据id查询商品接口

        //缓存穿透问题
        Shop shop = queryWithPassThrough(id);
        //解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicExpire(id);
        //调用封装类解决缓存穿透问题
        //Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,5L,TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /***
     * s端更新店铺操作
     * @param shop
     * @return
     */
    @Override
    @Transactional//事务控制
    public Result updateShop(Shop shop) {
        //更新数据库
        boolean u =updateById(shop);
        if(!u){
            throw new RuntimeException("更新数据库失败");
        }
        //删除缓存
        boolean f = stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        if(!f){
            throw new RuntimeException("删除缓存失败");
        }
        return Result.ok();
    }

    /***
     * 封装解决缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(!StrUtil.isBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
            //缓存命中，返回缓存中内容
        }
        //解决缓存穿透问题
        if(Objects.nonNull(shopJson)){
            //说明缓存的是空字符串
            return null;
        }
        //如果缓存中没有
        Shop shop = getById(id);
        if (shop ==null){
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",30,TimeUnit.MINUTES);
            //缓存中插入空对象解决缓存穿透问题
            return null;
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop));//放到缓存当中
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_KEY + id,30, TimeUnit.MINUTES);//设置缓存过期时间
        return shop;
    }

    /***
     * 利用互斥锁解决缓存击穿问题,同时解决穿透
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        if(!StrUtil.isBlank(shopJson)){
            return JSONUtil.toBean(shopJson,Shop.class);
            //缓存命中，返回缓存中内容
        }
        //解决缓存穿透问题
        if(Objects.nonNull(shopJson)){
            //说明缓存的是空字符串
            return null;
        }
        //如果缓存中没有，开始缓存重建

        //1.获取互斥锁
        Shop shop = null;
        try {
            boolean lock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);//使用商铺id作为锁的key
            //2.未获得互斥锁，休眠一段时间后重新尝试获得锁
            while (!lock){
                Thread.sleep(50);
                queryWithMutex(id);
            }

            //3.获得锁以后，根据id查询数据库，重建缓存
            shop = getById(id);
            Thread.sleep(200);//模拟缓存重建延时
            if (shop ==null){
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",30,TimeUnit.MINUTES);
                //缓存中插入空对象解决缓存穿透问题
                return null;
            }


            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop));//放到缓存当中
            stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_KEY + id,30, TimeUnit.MINUTES);//设置缓存过期时间
            //4.释放锁
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }
        return shop;
    }

    /**
     * 利用redis模拟互斥锁，setnx命令
     * @param key
     * @return
     */
    boolean tryLock(String key){
        return stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);//过期时间是为了防止死锁
    }

    /***
     * 释放互斥锁
     * @param key
     * @return
     */
    boolean unlock(String key){
        Boolean delete = stringRedisTemplate.delete(key);
        return delete;
    }

    /***
     * 缓存预热，把热点key缓存到redis当中
     * @param id
     */
    public void saveHot2Redis(Long id,Long expireTime){
        Shop shop = getById(id);
        try {
            Thread.sleep(200);//模拟缓存重建延时
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        //保存热点key到redis当中
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    /***
     * 逻辑过期解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //1.如果缓存没有命中，直接返回空置，不访问数据库，同时说明为非热点key
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //2.如果缓存中数据没有过期，直接返回缓存数据
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //数据过期重建缓存
        //获取店铺互斥锁
        Boolean lock = tryLock(RedisConstants.LOCK_SHOP_KEY+id);
        if(!lock){
            return shop;//如果没有获取到锁，直接返回
        }
        if(lock){
            CACHE_REBUILD_EXECUTOR.submit(()->{ saveHot2Redis(id,20L);
                unlock(RedisConstants.LOCK_SHOP_KEY+id);//释放锁
            });
        }
        return shop;
    }
}
