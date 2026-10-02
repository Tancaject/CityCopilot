package com.citycopilot.core.utils;

/** Core 使用的 Redis 键前缀、缓存 TTL 和互斥重建参数。 */
public class RedisConstants {
    /** 短信登录验证码键前缀，后接手机号。 */
    public static final String LOGIN_CODE_KEY = "login:code:";
    /** 验证码发送限流键前缀。 */
    public static final String LOGIN_CODE_RATE_LIMIT_KEY = "limit:login:code:";
    /** 验证码有效期，单位分钟。 */
    public static final Long LOGIN_CODE_TTL = 2L;
    /** 登录会话 Hash 键前缀，后接 token。 */
    public static final String LOGIN_USER_KEY = "login:token:";
    /** 登录会话滑动有效期，单位分钟。 */
    public static final Long LOGIN_USER_TTL = 30L;

    /** 负缓存基础有效期，单位分钟。 */
    public static final Long CACHE_NULL_TTL = 2L;
    /** 负缓存附加随机有效期上限，单位分钟。 */
    public static final Long CACHE_NULL_TTL_JITTER = 1L;

    /** 店铺缓存基础有效期，单位分钟。 */
    public static final Long CACHE_SHOP_TTL = 30L;
    /** 店铺缓存附加随机有效期上限，单位分钟。 */
    public static final Long CACHE_SHOP_TTL_JITTER = 10L;
    /** 单店 JSON 缓存键前缀。 */
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    /** 店铺分类列表缓存键。 */
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop-type";
    /** 高德地址解析结果缓存键前缀。 */
    public static final String CACHE_AMAP_GEOCODE_KEY = "cache:amap:geocode:";
    /** 高德 POI 搜索结果缓存键前缀。 */
    public static final String CACHE_AMAP_POI_KEY = "cache:amap:poi:";

    /** 店铺缓存重建锁键前缀。 */
    public static final String LOCK_SHOP_KEY = "lock:shop:";
    /** 缓存重建锁有效期，单位秒。 */
    public static final Long LOCK_SHOP_TTL = 10L;
    /** 竞争者等待其他请求重建缓存的总时长，单位毫秒。 */
    public static final Long CACHE_REBUILD_WAIT_MILLIS = 800L;
    /** 缓存重建竞争者的基础重试间隔，单位毫秒。 */
    public static final Long CACHE_REBUILD_RETRY_MILLIS = 25L;

    /** 仅用于导入旧版点赞事实；运行时点赞关系由 MySQL 持有。 */
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    /** 按店铺分类拆分的 Redis GEO 键前缀。 */
    public static final String SHOP_GEO_KEY = "shop:geo:";
    /** 全部店铺 Redis GEO 索引键。 */
    public static final String SHOP_GEO_ALL_KEY = "shop:geo:all";
    /** GEO 投影是否已完成初始构建的标志键。 */
    public static final String SHOP_GEO_INDEX_READY_KEY = "shop:geo:index:ready";
    /** GEO 投影水位和构建元数据键。 */
    public static final String SHOP_GEO_INDEX_META_KEY = "shop:geo:index:meta";
    /** 高德 API 分布式限流键前缀。 */
    public static final String AMAP_RATE_LIMIT_KEY = "limit:amap:";
    /** 高德 API 熔断状态键。 */
    public static final String AMAP_CIRCUIT_KEY = "circuit:amap";
    /** 高德连续失败次数键。 */
    public static final String AMAP_FAILURE_STREAK_KEY = "metrics:amap:failure-streak";
    /** 高德调用指标键前缀。 */
    public static final String AMAP_METRIC_KEY = "metrics:amap:";
    /** AI 接口限流键前缀。 */
    public static final String AI_RATE_LIMIT_KEY = "limit:ai:";
    /** 用户每日签到 Bitmap 键前缀。 */
    public static final String USER_SIGN_KEY = "sign:";
    /** 每日 UV HyperLogLog 键前缀。 */
    public static final String UV_KEY = "uv:";
}
