package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisDistributeLock;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.Synchronized;
import org.redisson.RedissonScript;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Objects;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    IVoucherService voucherService;

    @Autowired
    ISeckillVoucherService seckillVoucherService;

    @Autowired
    RedisIdWorker redisIdWorker;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("secKill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
       //异步编程优化秒杀业务
        UserDTO user = UserHolder.getUser();

        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), user.getId().toString());
        int re = result.intValue();
        if(re !=0){
            return Result.fail(re == 1?"库存不足":"用户已经下单");
        }
        //将订单保存到阻塞队列中由其他线程处理

        //返回订单编号
        long orderId = redisIdWorker.nextId("secKillOrder");
        return Result.ok(orderId);

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId,SeckillVoucher seckillVoucher){
        //解决一人一单问题
        int count = query().eq("user_id", UserHolder.getUser().getId()).eq("voucher_id", voucherId).count();
        if(count>0){
            return Result.fail("用户已经下单");
        }

        //判断是否还有库存
        if(seckillVoucher.getStock()<=0){
            return Result.fail("库存为0");
        }

        //下单
        //乐观锁解决超卖问题
        boolean seckillOrder = seckillVoucherService.update().setSql("stock = stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();

        if(!seckillOrder){
            return Result.fail("下单失败");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setId(redisIdWorker.nextId("secKillOrder"));//订单id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        save(voucherOrder);
        return Result.ok(voucherOrder.getId());

    }

//
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //判断优惠券是否过期
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        if(LocalDateTime.now().isBefore(beginTime)){
//            return Result.fail("时间未开始");
//        }
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        if(LocalDateTime.now().isAfter(endTime)){
//            return  Result.fail("时间已经结束");
//        }
//        Long userId = UserHolder.getUser().getId();
////        synchronized(userId.toString().intern()){
////            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
////            return  proxy.createVoucherOrder(voucherId,seckillVoucher);
////        }改进，使用redis实现分布式锁
////        RedisDistributeLock redisDistributeLock = new RedisDistributeLock(stringRedisTemplate,userId.toString());
////        boolean lock = redisDistributeLock.tryLock(1000);
////        if(!lock){
////            return Result.fail("一个用户只允许下一单");
////        }
////        try {
////            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
////            return  proxy.createVoucherOrder(voucherId,seckillVoucher);
////        }finally {
////            redisDistributeLock.unlock();//释放分布式锁
////        }redis实现分布式锁，无法重入，重试，超时时间不好确定
//
//        RLock lock = redissonClient.getLock(userId.toString());
//        boolean isLock = false;
//        try {
//            isLock = !lock.tryLock(1,10, TimeUnit.SECONDS);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//
//        if(isLock){
//            return Result.fail("一个用户只允许下一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return  proxy.createVoucherOrder(voucherId,seckillVoucher);
//        }finally {
//            lock.unlock();//释放分布式锁
//        }
//    }

}
