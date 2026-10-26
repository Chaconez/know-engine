-- =============================================================================
-- KnowEngine 演示数据 —— 业务库（MySQL）
--
-- 场景设定：星驰汽车（虚构品牌）智能客服。客户「张伟」已购一台星驰 X5，
--          知识库覆盖售前/售后/技术指导/投诉/营销五大意图。
--
-- 执行方式：mysql -h <host> -u <user> -p know_engine < demo-data.sql
--
-- 幂等性：全部使用 INSERT ... ON DUPLICATE KEY UPDATE，可反复执行。
-- 依赖：knowledge_document / car_info / my_car / car_order / user_info 表已建（见 sql/tables.sql）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. 演示账号（已存在则跳过，不覆盖密码，避免影响你现有账号）
-- -----------------------------------------------------------------------------
INSERT INTO `user_info` (`phone`, `password`, `name`, `nickname`)
VALUES ('18650567815', '123456', '张伟', '老张')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `nickname` = VALUES(`nickname`);

-- 客户登录后 sa-token 的 loginId 为客户主键 id，业务表的 user_id 必须与之对应
SET @uid = (SELECT `id` FROM `user_info` WHERE `phone` = '18650567815' LIMIT 1);

-- 员工账号：文档管理/问答缓存审核需要（页面 staff-login.html）
INSERT INTO `staff_info` (`emp_id`, `name`, `password`, `job`, `status`)
VALUES ('TEST001', '演示管理员', '123456', '知识库管理员', 'ON_JOB')
ON DUPLICATE KEY UPDATE `job` = VALUES(`job`), `status` = VALUES(`status`);


-- -----------------------------------------------------------------------------
-- 1. 车型库（car_info）
--    - 售前咨询、营销政策意图下的「选择车型」卡片数据来源
--    - 也是 text-to-sql 结构化查询的演示数据
-- -----------------------------------------------------------------------------
INSERT INTO `car_info`
(`info_id`, `brand`, `model_name`, `model_year`, `version`, `full_name`, `vehicle_type`,
 `fuel_type`, `seat_count`, `displacement`, `motor_power`, `range_km`, `guide_price`,
 `color_options`, `dimensions`, `wheelbase`, `manufacturer`, `status`, `description`)
VALUES
('CAR-X5-2025', '星驰', 'X5', 2025, '尊享四驱版', '星驰 X5 2025款 尊享四驱版', 'SUV',
 '增程', 5, 1.5, 250.00, 1200, 35.80,
 '珍珠白,曜石黑,深海蓝,晨曦金', '4920x1960x1780', 2980, '星驰汽车', '在售',
 '中大型增程 SUV，CLTC 综合续航 1200km，纯电续航 285km，标配空气悬架与四驱系统。'),

('CAR-S3-2025', '星驰', 'S3', 2025, '智驾版', '星驰 S3 2025款 智驾版', '轿车',
 '纯电', 5, NULL, 200.00, 705, 22.90,
 '象牙白,极夜黑,星云灰,天空蓝', '4780x1860x1490', 2840, '星驰汽车', '在售',
 '中型纯电轿车，CLTC 续航 705km，全系标配激光雷达与城市 NOA。'),

('CAR-M7-2025', '星驰', 'M7', 2025, '家用七座版', '星驰 M7 2025款 家用七座版', 'MPV',
 '插混', 7, 1.5, 180.00, 1100, 28.60,
 '珍珠白,曜石黑,暖沙金', '5180x1965x1800', 3080, '星驰汽车', '在售',
 '中大型插混 MPV，CLTC 综合续航 1100km，二排零重力座椅，主打家庭出行。')
ON DUPLICATE KEY UPDATE
    `brand`        = VALUES(`brand`),
    `model_name`   = VALUES(`model_name`),
    `model_year`   = VALUES(`model_year`),
    `version`      = VALUES(`version`),
    `full_name`    = VALUES(`full_name`),
    `vehicle_type` = VALUES(`vehicle_type`),
    `fuel_type`    = VALUES(`fuel_type`),
    `seat_count`   = VALUES(`seat_count`),
    `displacement` = VALUES(`displacement`),
    `motor_power`  = VALUES(`motor_power`),
    `range_km`     = VALUES(`range_km`),
    `guide_price`  = VALUES(`guide_price`),
    `color_options`= VALUES(`color_options`),
    `dimensions`   = VALUES(`dimensions`),
    `wheelbase`    = VALUES(`wheelbase`),
    `status`       = VALUES(`status`),
    `description`  = VALUES(`description`);


-- -----------------------------------------------------------------------------
-- 2. 我的车辆（my_car）
--    作用：绕过「售后维修与保养 / 车辆使用与技术指导」意图的车辆校验，
--          否则对话会返回 [WARN]:您还没有添加车辆信息
--    注意：user_id 必须是客户的登录 id（上面的 @uid），不是手机号
-- -----------------------------------------------------------------------------
INSERT INTO `my_car`
(`car_id`, `user_id`, `car_info_id`, `nickname`, `full_name`, `order_id`, `plate_number`,
 `color`, `vin`, `engine_number`, `purchase_date`, `purchase_price`, `mileage`,
 `register_date`, `insurance_expire_date`, `inspection_expire_date`, `remark`)
VALUES
('MYCAR-0001', @uid, 'CAR-X5-2025', '小白', '星驰 X5 2025款 尊享四驱版', 'ORD-2025-0001', '川A·88X5L',
 '珍珠白', 'LXCX5A2025DEMO001', 'EN2025DEMO001', '2025-03-15', 358000.00, 12860,
 '2025-03-18', '2027-03-14', '2027-03-31', '演示车辆，用于触发选车卡片与技术指导知识库检索。')
ON DUPLICATE KEY UPDATE
    `user_id`      = VALUES(`user_id`),
    `car_info_id`  = VALUES(`car_info_id`),
    `nickname`     = VALUES(`nickname`),
    `full_name`    = VALUES(`full_name`),
    `order_id`     = VALUES(`order_id`),
    `color`        = VALUES(`color`),
    `mileage`      = VALUES(`mileage`),
    `remark`       = VALUES(`remark`);


-- -----------------------------------------------------------------------------
-- 3. 车辆订单（car_order）
--    作用：演示 text-to-sql 结构化查询（"我的订单到哪一步了""保养套餐花了多少钱"）
-- -----------------------------------------------------------------------------
INSERT INTO `car_order`
(`order_id`, `user_id`, `car_id`, `order_no`, `order_type`, `order_status`, `brand`, `model`,
 `color`, `vin`, `seller_name`, `seller_contact`, `vehicle_price`, `purchase_tax`,
 `insurance_fee`, `other_fee`, `total_amount`, `discount_amount`, `actual_amount`,
 `payment_method`, `order_date`, `delivery_date`, `remark`)
VALUES
('ORD-2025-0001', @uid, 'MYCAR-0001', 'XC20250315001', '购买', '已完成', '星驰', 'X5',
 '珍珠白', 'LXCX5A2025DEMO001', '星驰汽车成都高新体验中心', '028-8888-0001',
 358000.00, 31681.42, 8800.00, 2000.00, 400481.42, 20000.00, 380481.42,
 '全款', '2025-03-15', '2025-03-18', '含首年保险与上牌服务'),

('ORD-2026-0001', @uid, 'MYCAR-0001', 'XC20261020008', '保养', '待支付', '星驰', 'X5',
 '珍珠白', 'LXCX5A2025DEMO001', '星驰汽车成都高新体验中心', '028-8888-0001',
 1680.00, 0.00, 0.00, 200.00, 1880.00, 0.00, 1880.00,
 '全款', '2026-10-20', NULL, '2万公里常规保养套餐（含机油机滤+空调滤芯）')
ON DUPLICATE KEY UPDATE
    `user_id`        = VALUES(`user_id`),
    `car_id`         = VALUES(`car_id`),
    `order_status`   = VALUES(`order_status`),
    `total_amount`   = VALUES(`total_amount`),
    `actual_amount`  = VALUES(`actual_amount`),
    `remark`         = VALUES(`remark`);


-- -----------------------------------------------------------------------------
-- 4. 校验：执行后应能看到以下数据
-- -----------------------------------------------------------------------------
SELECT '=== 演示客户 ===' AS section;
SELECT id, phone, name, nickname FROM user_info WHERE phone = '18650567815';

SELECT '=== 我的车辆 ===' AS section;
SELECT car_id, user_id, plate_number, full_name, mileage FROM my_car WHERE user_id = @uid;

SELECT '=== 我的订单 ===' AS section;
SELECT order_no, order_type, order_status, actual_amount FROM car_order WHERE user_id = @uid;

SELECT '=== 可售车型 ===' AS section;
SELECT info_id, full_name, guide_price, range_km FROM car_info WHERE status = '在售';
