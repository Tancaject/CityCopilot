# CityCopilot Eval Report

- provider: offline
- cases: 30
- passed: 30
- mode_accuracy: 100%
- tool_accuracy: 100%
- keyword_accuracy: 100%
- avg_latency_ms: 0
- avg_estimated_tokens: 27

## Failure Reasons

- none

| Case | Pass | Mode | Tools | Keywords | Failure reasons | Expected tools | Predicted tools | Latency ms | Est. tokens |
|---|---:|---|---:|---:|---|---|---|---:|---:|
|nearby_hotpot|true|REACT|true|true||search_nearby_shops|search_nearby_shops|11|27|
|shop_vouchers|true|REACT|true|true||list_vouchers_by_shop|list_vouchers_by_shop|0|32|
|my_orders|true|REACT|true|true||get_my_orders|get_my_orders|0|18|
|hotpot_order_guard|true|PLAN_EXECUTE|true|true||search_nearby_shops,list_vouchers_by_shop,place_seckill_order|search_nearby_shops,list_vouchers_by_shop,place_seckill_order|0|65|
|pay_order_guard|true|PLAN_EXECUTE|true|true||pay_order|pay_order|0|22|
|refund_order_guard|true|PLAN_EXECUTE|true|true||refund_order|refund_order|0|19|
|cancel_order_guard|true|PLAN_EXECUTE|true|true||cancel_order|cancel_order|0|19|
|geo_provider_status|true|REACT|true|true||get_geo_provider_status|get_geo_provider_status|1|37|
|audit_logs|true|REACT|true|true||query_audit_logs|query_audit_logs|0|29|
|ops_summary|true|REACT|true|true||ops_summary|ops_summary|0|17|
|shop_detail|true|REACT|true|true||get_shop_detail|get_shop_detail|0|23|
|shop_types|true|REACT|true|true||list_shop_types|list_shop_types|0|25|
|nearby_hotpot_english|true|REACT|true|true||search_nearby_shops|search_nearby_shops|0|21|
|voucher_english|true|REACT|true|true||list_vouchers_by_shop|list_vouchers_by_shop|0|25|
|hot_blogs|true|REACT|true|true||list_hot_blogs|list_hot_blogs|0|22|
|blog_detail|true|REACT|true|true||get_blog_detail|get_blog_detail|0|23|
|blog_likes|true|REACT|true|true||list_blog_likes|list_blog_likes|0|27|
|follow_feed|true|REACT|true|true||get_follow_feed|get_follow_feed|0|23|
|check_follow|true|REACT|true|true||check_follow_user|check_follow_user|0|32|
|common_follows|true|REACT|true|true||list_common_follows|list_common_follows|0|27|
|like_blog_guard|true|PLAN_EXECUTE|true|true||like_blog|like_blog|0|24|
|follow_user_guard|true|PLAN_EXECUTE|true|true||set_follow_user|set_follow_user|0|25|
|geocode_address|true|REACT|true|true||geocode_address|geocode_address|0|27|
|search_geo_poi|true|REACT|true|true||search_geo_poi|search_geo_poi|0|24|
|geo_batch_jobs|true|REACT|true|true||list_geo_batch_jobs|list_geo_batch_jobs|0|29|
|start_geo_batch_guard|true|PLAN_EXECUTE|true|true||start_batch_geocode|start_batch_geocode|0|35|
|retry_geo_batch_guard|true|PLAN_EXECUTE|true|true||retry_geo_batch_failed_items|retry_geo_batch_failed_items|0|41|
|audit_retention|true|REACT|true|true||get_audit_retention_status|get_audit_retention_status|0|32|
|english_place_order|true|PLAN_EXECUTE|true|true||place_seckill_order|place_seckill_order|0|27|
|english_ops_summary|true|REACT|true|true||ops_summary|ops_summary|0|14|
