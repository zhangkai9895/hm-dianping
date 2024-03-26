package com.hmdp;

import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HmDianPingApplicationTests {


    @Autowired
    RedisIdWorker redisIdWorker;


    @Test
    void testRedisIdWorker(){
        System.out.println(redisIdWorker.nextId("test"));

    }
}
