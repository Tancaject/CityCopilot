package com.citycopilot.core.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final String LOGIN_CODE_RATE_LIMIT_KEY = "limit:login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;
    public static final Long CACHE_NULL_TTL_JITTER = 1L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final Long CACHE_SHOP_TTL_JITTER = 10L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop-type";
    public static final String CACHE_AMAP_GEOCODE_KEY = "cache:amap:geocode:";
    public static final String CACHE_AMAP_POI_KEY = "cache:amap:poi:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;
    public static final Long CACHE_REBUILD_WAIT_MILLIS = 800L;
    public static final Long CACHE_REBUILD_RETRY_MILLIS = 25L;

    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String SHOP_GEO_ALL_KEY = "shop:geo:all";
    public static final String SHOP_GEO_INDEX_READY_KEY = "shop:geo:index:ready";
    public static final String SHOP_GEO_INDEX_META_KEY = "shop:geo:index:meta";
    public static final String AMAP_RATE_LIMIT_KEY = "limit:amap:";
    public static final String AMAP_CIRCUIT_KEY = "circuit:amap";
    public static final String AMAP_FAILURE_STREAK_KEY = "metrics:amap:failure-streak";
    public static final String AMAP_METRIC_KEY = "metrics:amap:";
    public static final String AI_RATE_LIMIT_KEY = "limit:ai:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String UV_KEY = "uv:";
}
