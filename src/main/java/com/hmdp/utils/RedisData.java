package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/***
 * 逻辑缓存数据，添加过期时间字段
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
