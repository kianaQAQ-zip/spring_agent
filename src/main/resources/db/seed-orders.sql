-- ============================================================
-- 订单种子数据：单商家多平台，300 条，时间散布最近 30 天
-- 平台：taobao / jd / pdd / douyin / kuaishou / wechat
-- 状态：DELIVERED / SHIPPED / PAID / REFUNDING / PENDING / REFUNDED
-- 依赖：init.sql 的 orders / order_trace 表已创建
-- 幂等：仅当 orders 表为空时才灌入（可重复执行）
-- ============================================================

INSERT INTO orders
    (id, order_id, tenant_id, platform, buyer_name, buyer_phone, status,
     amount, item_title, quantity, address, carrier, tracking_no, created_at, updated_at)
SELECT
    md5(random()::text),
    'ORD-' || (1000 + g),
    'default',
    (ARRAY['taobao','jd','pdd','douyin','kuaishou','wechat'])[1 + (g % 6)],
    (ARRAY['张','李','王','刘','陈','杨','黄','赵','吴','周'])[1 + (g % 10)]
        || (ARRAY['伟','芳','娜','敏','静','磊','洋','勇','艳','杰'])[1 + ((g * 7) % 10)],
    '138' || lpad(((10000000 + (g * 12345) % 90000000))::text, 8, '0'),
    (CASE
        WHEN g % 10 < 5 THEN 'DELIVERED'
        WHEN g % 10 < 7 THEN 'SHIPPED'
        WHEN g % 10 < 8 THEN 'PAID'
        WHEN g % 10 < 9 THEN 'REFUNDING'
        ELSE 'PENDING'
    END),
    (50 + (g * 37) % 450)::numeric,
    (ARRAY['无线蓝牙耳机','磁吸充电宝','316不锈钢保温杯','纯棉短袖T恤','便携蓝牙音箱',
           'Type-C数据线','补水面膜','坚果零食礼盒','机械键盘','智能手环'])[1 + (g % 10)],
    1 + (g % 3),
    (ARRAY['浙江省杭州市西湖区文三路','广东省深圳市南山区科技园','北京市朝阳区望京','上海市浦东新区张江',
           '四川省成都市高新区天府大道','江苏省南京市鼓楼区中山路'])[1 + (g % 6)]
        || ' ' || (100 + g) || '号',
    (CASE (g % 6)
        WHEN 1 THEN '京东物流' WHEN 2 THEN '中通快递' WHEN 3 THEN '圆通速递'
        WHEN 4 THEN '申通快递' WHEN 5 THEN '韵达快递' ELSE '顺丰速运' END),
    (CASE (g % 6)
        WHEN 1 THEN 'JD' WHEN 2 THEN 'ZT' WHEN 3 THEN 'YT'
        WHEN 4 THEN 'ST' WHEN 5 THEN 'YD' ELSE 'SF' END) || lpad(((100000000 + g * 987654) % 900000000)::text, 9, '0'),
    now() - ((g % 30) * interval '1 day') - ((g % 24) * interval '1 hour'),
    now() - ((g % 30) * interval '1 day')
FROM generate_series(1, 300) AS g
WHERE NOT EXISTS (SELECT 1 FROM orders LIMIT 1);

-- 物流轨迹：已发货及之后状态（SHIPPED / DELIVERED / REFUNDING）才生成。
-- 注意：DELIVERED 的最后一条直接就是「已签收」，SHIPPED/REFUNDING 停留在「派送中」。
INSERT INTO order_trace (id, order_id, tenant_id, platform, seq, happened_at, node)
SELECT
    md5(random()::text),
    o.order_id,
    'default',
    o.platform,
    t.seq,
    o.created_at + (t.seq * interval '8 hours'),
    (CASE
        WHEN t.seq = 1 THEN '订单已提交，等待商家发货'
        WHEN t.seq = 2 THEN '包裹已揽收，发往分拨中心'
        WHEN t.seq = 3 THEN '运输中，已到达目的地转运中心'
        WHEN o.status = 'DELIVERED' THEN '已签收，感谢您的使用'
        ELSE '派送中，快递员正在为您配送' END)
FROM orders o
CROSS JOIN generate_series(1, 4) AS t(seq)
WHERE o.status IN ('SHIPPED', 'DELIVERED', 'REFUNDING')
  AND NOT EXISTS (SELECT 1 FROM order_trace LIMIT 1);
