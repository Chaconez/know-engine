-- 知识文档表
create TABLE `knowledge_document` (
    `doc_id`        BIGINT        NOT NULL AUTO_INCREMENT comment '文档ID',
    `doc_title`     VARCHAR(1024) NOT NULL comment '文档标题',
    `expire_date`   DATE          NULL     comment '文档失效日期',
    `status`        VARCHAR(32)   NOT NULL comment '状态：INIT, UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED',
    `accessible_by` VARCHAR(1024) NULL     comment '可见范围',
    `description`   VARCHAR(512)  NULL     comment '文档描述',
    `knowledge_base_type` VARCHAR(32) NULL comment '知识库类型：DOCUMENT_SEARCH, DATA_QUERY',
    `extension`     TEXT          NULL     comment '扩展字段，保存JSON字符串',
    `current_version_id` BIGINT  NULL     comment '当前激活版本ID，指向 knowledge_document_version.version_id',
    `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '修改时间',
    `lock_version` INT           NOT NULL DEFAULT 0 comment '乐观锁版本号',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 comment '是否删除：0-未删除，1-已删除',
    PRIMARY KEY (`doc_id`),
    -- 为状态字段添加索引，优化定时任务扫表性能
    INDEX `idx_status` (`status`),
    -- 复合索引：状态+文档ID，优化分页查询性能
    INDEX `idx_status_doc_id` (`status`, `doc_id`),
    -- 创建时间索引，优化按时间排序查询
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '知识文档表';

-- 文档版本表（存储文档每个版本的快照信息）
create TABLE `knowledge_document_version` (
    `version_id`      BIGINT       NOT NULL AUTO_INCREMENT comment '版本ID',
    `doc_id`          BIGINT       NOT NULL comment '关联文档ID（knowledge_document.doc_id）',
    `version`         VARCHAR(32)  NOT NULL DEFAULT '1.0.0' comment '版本号（语义化版本，如 1.0.0）',
    `doc_url`         VARCHAR(2048) NULL    comment '该版本文档URL（MinIO原始文件）',
    `converted_doc_url` VARCHAR(2048) NULL  comment '该版本转换后的文档URL',
    `content_hash`    VARCHAR(64)  NULL    comment '该版本文档内容哈希值（SHA-256）',
    `status`          VARCHAR(32)  NOT NULL comment '版本状态：UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED, STORED',
    `upload_user`     VARCHAR(255) NULL    comment '该版本上传用户',
    `changelog`       VARCHAR(1024) NULL   comment '版本变更说明',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '修改时间',
    `lock_version`   INT          NOT NULL DEFAULT 0 comment '乐观锁版本号',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 comment '是否删除：0-未删除，1-已删除',
    PRIMARY KEY (`version_id`),
    -- 同一文档版本号唯一
    UNIQUE KEY `uk_doc_version` (`doc_id`, `version`),
    -- 文档ID索引，用于查询文档所有版本
    INDEX `idx_doc_id` (`doc_id`),
    -- 内容哈希索引，用于跨版本去重
    INDEX `idx_content_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '文档版本表';

-- 知识片段表
create TABLE `knowledge_segment` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT comment '片段ID',
    `text`        LONGTEXT     NOT NULL comment '文本内容',
    `chunk_id`    VARCHAR(255) NULL     comment '分片ID',
    `metadata`    VARCHAR(2048) NULL     comment '元数据',
    `document_id` BIGINT       NOT NULL comment '所属文档ID',
    `document_version` BIGINT       NULL     comment '所属文档版本ID（knowledge_document_version.version_id）',
    `chunk_order` INT       NOT NULL comment '顺序',
    `embedding_id` VARCHAR(255) NULL     comment '嵌入ID',
    `status` VARCHAR(255) NULL     comment '状态：STORED, VECTOR_STORED',
    `skip_embedding` INT NULL     comment '是否跳过嵌入生成',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '修改时间',
    `lock_version`  INT          NOT NULL DEFAULT 0 comment '乐观锁版本号',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 comment '是否删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    -- 文档ID索引
    INDEX `idx_document_id` (`document_id`),
    -- 复合索引：文档ID+顺序，优化按文档查询并排序
    INDEX `idx_document_id_chunk_order` (`document_id`, `chunk_order`),
    -- 复合索引：文档ID+状态+跳过嵌入，优化向量化补偿任务查询
    INDEX `idx_document_status_skip` (`document_id`, `status`, `skip_embedding`),
    -- 文档版本ID索引，优化按版本查询分段
    INDEX `idx_document_version` (`document_version`),
    -- 状态索引，优化按状态查询
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '知识片段表';

-- 表元数据表（存储动态创建的表的元数据信息）
create TABLE `table_meta` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT comment '主键ID',
    `table_name`   VARCHAR(128) NOT NULL comment '表名',
    `description`  VARCHAR(512) NULL     comment '表描述',
    `create_sql`   TEXT         NULL     comment '建表语句',
    `columns_info` TEXT         NULL     comment '字段信息（JSON格式）',
    `version_id`   BIGINT       NULL     comment '关联的文档版本ID（knowledge_document_version.version_id），DATA_QUERY 多版本管理使用',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '修改时间',
    `lock_version` INT          NOT NULL DEFAULT 0 comment '乐观锁版本号',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 comment '是否删除：0-未删除，1-已删除',
    PRIMARY KEY (`id`),
    -- 表名唯一索引
    UNIQUE INDEX `uk_table_name` (`table_name`),
    -- 版本ID索引，用于按版本清理与查询
    INDEX `idx_version_id` (`version_id`),
    -- 创建时间索引
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = '表元数据表';

-- AI对话会话表
create TABLE `chat_conversation` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT comment '主键ID',
    `conversation_id` VARCHAR(64) NOT NULL comment '会话唯一标识',
    `user_id`         VARCHAR(64) NOT NULL comment '用户ID',
    `title`           VARCHAR(512) NULL    comment '会话标题',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '修改时间',
    `lock_version` INT          NOT NULL DEFAULT 0 comment '乐观锁版本号',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 comment '是否删除：0-未删除，1-已删除',
    `status`          VARCHAR(32) NOT NULL DEFAULT 'active' comment '状态',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = 'AI对话会话表';

-- AI对话消息表
create TABLE `chat_message` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT comment '主键ID',
    `message_id`       VARCHAR(64)  NOT NULL comment '消息唯一标识',
    `conversation_id`  VARCHAR(64)  NOT NULL comment '所属会话ID',
    `type`             VARCHAR(32)  NOT NULL comment '角色：USER/ASSISTANT',
    `content`          LONGTEXT     NULL     comment '消息内容',
    `transform_content` LONGTEXT    NULL     comment '改写后的内容',
    `token_count`      INT          NULL     comment 'Token数量',
    `model_name`       VARCHAR(128) NULL     comment '使用的模型名称',
    `rag_references`   JSON         NULL     comment 'RAG引用内容JSON数组，包含document_id、document_title、chunk_id、chunk_content、similarity_score、retrieval_source等字段',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '修改时间',
    `lock_version` INT          NOT NULL DEFAULT 0 comment '乐观锁版本号',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 comment '是否删除：0-未删除，1-已删除',
    `metadata`         JSON         NULL     comment '扩展元数据JSON格式',
    `cache_hit`        TINYINT      NOT NULL DEFAULT 0 comment '本次回答是否命中缓存：0未命中、1命中',
    `helpful`          TINYINT      NULL comment '回答评价：NULL未评价、1有帮助、0无帮助',
    `feedback_comment` VARCHAR(1000) NULL comment '用户评价说明',
    `feedback_at`      DATETIME(6)  NULL comment '首次评价时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_id` (`message_id`),
    INDEX `idx_conversation_id` (`conversation_id`),
    INDEX `idx_create_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment = 'AI对话消息表';


-- 车型信息表
create TABLE IF NOT EXISTS `car_info` (
    `id` BIGINT NOT NULL AUTO_INCREMENT comment '主键ID',
    `info_id` VARCHAR(64) NOT NULL comment '车型唯一标识',
    `brand` VARCHAR(64) DEFAULT NULL comment '品牌（如：Tesla、比亚迪、宝马等）',
    `model_name` VARCHAR(128) DEFAULT NULL comment '型号名称（如：Model 3、汉、3系等）',
    `model_year` INT DEFAULT NULL comment '年款（如：2025、2024等）',
    `version` VARCHAR(128) DEFAULT NULL comment '版本描述（如：焕新版、长续航版、运动版等）',
    `full_name` VARCHAR(256) DEFAULT NULL comment '全称（如：Tesla Model 3 2025焕新版）',
    `vehicle_type` VARCHAR(32) DEFAULT NULL comment '车辆类型：轿车/SUV/MPV/跑车/皮卡等',
    `fuel_type` VARCHAR(32) DEFAULT NULL comment '燃油类型：汽油/柴油/电动/混动/氢能源等',
    `seat_count` INT DEFAULT NULL comment '座位数',
    `displacement` DECIMAL(4,1) DEFAULT NULL comment '排量（L），燃油车使用',
    `motor_power` DECIMAL(8,2) DEFAULT NULL comment '电机功率（kW），电动车使用',
    `range_km` INT DEFAULT NULL comment '续航里程（km），电动车使用',
    `guide_price` DECIMAL(12,2) DEFAULT NULL comment '官方指导价（万元）',
    `color_options` VARCHAR(512) DEFAULT NULL comment '车身颜色选项（多个颜色用逗号分隔）',
    `dimensions` VARCHAR(64) DEFAULT NULL comment '车身尺寸（长x宽x高，单位mm）',
    `wheelbase` INT DEFAULT NULL comment '轴距（mm）',
    `manufacturer` VARCHAR(128) DEFAULT NULL comment '生产厂商',
    `status` VARCHAR(32) DEFAULT '在售' comment '车型状态：在售/停售/即将上市',
    `image_url` VARCHAR(512) DEFAULT NULL comment '车型图片URL',
    `description` TEXT DEFAULT NULL comment '车型描述',
    `remark` TEXT DEFAULT NULL comment '备注',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_info_id` (`info_id`),
    KEY `idx_brand` (`brand`),
    KEY `idx_vehicle_type` (`vehicle_type`),
    KEY `idx_fuel_type` (`fuel_type`),
    KEY `idx_status` (`status`),
    KEY `idx_full_name` (`full_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment='车型信息表';


-- 我的车辆信息表
create TABLE IF NOT EXISTS `my_car` (
    `id` BIGINT NOT NULL AUTO_INCREMENT comment '主键ID',
    `car_id` VARCHAR(64) NOT NULL comment '车辆唯一标识',
    `user_id` VARCHAR(64) DEFAULT NULL comment '用户ID，标识车辆归属',
    `car_info_id` VARCHAR(64) DEFAULT NULL comment '关联的车型信息ID',
    `nickname` VARCHAR(128) DEFAULT NULL comment '车辆昵称（车主自定义名称）',
    `full_name` VARCHAR(256) DEFAULT NULL comment '车辆全称（如：Tesla Model 3 2025焕新版）',
    `image_url` VARCHAR(512) DEFAULT NULL comment '车辆图片URL',
    `order_id` VARCHAR(64) DEFAULT NULL comment '关联的购车订单ID',
    `plate_number` VARCHAR(32) NOT NULL comment '车牌号',
    `color` VARCHAR(32) DEFAULT NULL comment '车辆颜色（具体颜色，如：珍珠白、深海蓝等）',
    `vin` VARCHAR(32) DEFAULT NULL comment '车辆识别代号(VIN码)',
    `engine_number` VARCHAR(64) DEFAULT NULL comment '发动机号',
    `purchase_date` DATE DEFAULT NULL comment '购买日期',
    `purchase_price` DECIMAL(15,2) DEFAULT NULL comment '购买价格',
    `mileage` INT DEFAULT 0 comment '行驶里程(公里)',
    `register_date` DATE DEFAULT NULL comment '注册日期',
    `insurance_expire_date` DATE DEFAULT NULL comment '保险到期日',
    `inspection_expire_date` DATE DEFAULT NULL comment '年检到期日',
    `remark` TEXT DEFAULT NULL comment '备注',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_car_id` (`car_id`),
    UNIQUE KEY `uk_plate_number` (`plate_number`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_car_info_id` (`car_info_id`),
    KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment='我的车辆信息表';


-- 车辆订单表
create TABLE IF NOT EXISTS `car_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT comment '主键ID',
    `order_id` VARCHAR(64) NOT NULL comment '订单唯一标识',
    `user_id` VARCHAR(64) DEFAULT NULL comment '用户ID，标识订单归属',
    `car_id` VARCHAR(64) DEFAULT NULL comment '关联的车辆ID',
    `order_no` VARCHAR(64) NOT NULL comment '订单编号',
    `order_type` VARCHAR(32) DEFAULT NULL comment '订单类型：购买/出售/置换',
    `order_status` VARCHAR(32) DEFAULT '待支付' comment '订单状态：待支付/已支付/已完成/已取消',
    `brand` VARCHAR(64) DEFAULT NULL comment '车辆品牌',
    `model` VARCHAR(128) DEFAULT NULL comment '车辆型号',
    `color` VARCHAR(32) DEFAULT NULL comment '车辆颜色',
    `vin` VARCHAR(32) DEFAULT NULL comment '车辆VIN码',
    `seller_name` VARCHAR(256) DEFAULT NULL comment '卖家/经销商名称',
    `seller_contact` VARCHAR(128) DEFAULT NULL comment '卖家/经销商联系方式',
    `vehicle_price` DECIMAL(15,2) DEFAULT NULL comment '车辆价格',
    `purchase_tax` DECIMAL(15,2) DEFAULT NULL comment '购置税',
    `insurance_fee` DECIMAL(15,2) DEFAULT NULL comment '保险费用',
    `other_fee` DECIMAL(15,2) DEFAULT NULL comment '其他费用',
    `total_amount` DECIMAL(15,2) DEFAULT NULL comment '订单总金额',
    `discount_amount` DECIMAL(15,2) DEFAULT NULL comment '优惠金额',
    `actual_amount` DECIMAL(15,2) DEFAULT NULL comment '实际支付金额',
    `payment_method` VARCHAR(32) DEFAULT NULL comment '付款方式：全款/分期',
    `order_date` DATE DEFAULT NULL comment '订单日期',
    `delivery_date` DATE DEFAULT NULL comment '交付日期',
    `remark` TEXT DEFAULT NULL comment '备注',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_car_id` (`car_id`),
    KEY `idx_order_status` (`order_status`),
    KEY `idx_order_type` (`order_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment='车辆订单表';


-- 员工信息表
create TABLE IF NOT EXISTS `staff_info` (
    `id`                      BIGINT       NOT NULL AUTO_INCREMENT comment '主键ID',
    `emp_id`                  VARCHAR(64)  DEFAULT NULL comment '工号',
    `name`                    VARCHAR(64)  DEFAULT NULL comment '姓名',
    `password`                VARCHAR(128) DEFAULT NULL comment '登录密码',
    `job`                     VARCHAR(128) DEFAULT NULL comment '岗位',
    `entry_time`              DATE         DEFAULT NULL comment '入职时间',
    `birthday`                DATE         DEFAULT NULL comment '生日',
    `educational_background`  VARCHAR(32)  DEFAULT NULL comment '学历：junior、undergraduate、master、doctor',
    `director_id`             BIGINT       DEFAULT NULL comment '主管ID',
    `dept_id`                 BIGINT       DEFAULT NULL comment '部门ID',
    `duty`                    VARCHAR(512) DEFAULT NULL comment '工作职责',
    `motto`                   VARCHAR(256) DEFAULT NULL comment '个性签名',
    `pic_url`                 VARCHAR(512) DEFAULT NULL comment '头像地址',
    `status`                  VARCHAR(32)  DEFAULT 'ON_JOB' comment '状态：ON_JOB-在职、OFF_JOB-已离职',
    `resignation_time`        DATE         DEFAULT NULL comment '离职时间',
    `created_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at`              DATETIME     DEFAULT CURRENT_TIMESTAMP ON update CURRENT_TIMESTAMP comment '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_emp_id` (`emp_id`),
    KEY `idx_dept_id` (`dept_id`),
    KEY `idx_director_id` (`director_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment='员工信息表';

-- ============================================================
-- 客户信息表（网页端客户登录使用）
-- ============================================================
CREATE TABLE IF NOT EXISTS `user_info` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT comment '主键ID',
    `phone`        VARCHAR(20)  NOT NULL comment '手机号（登录账号）',
    `password`     VARCHAR(128) NOT NULL comment '登录密码',
    `name`         VARCHAR(64)  DEFAULT NULL comment '姓名',
    `nickname`     VARCHAR(128) DEFAULT NULL comment '昵称',
    `avatar`       VARCHAR(512) DEFAULT NULL comment '头像地址',
    `status`       VARCHAR(32)  DEFAULT 'ACTIVE' comment '状态：ACTIVE-正常、FROZEN-冻结',
    `created_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP comment '创建时间',
    `updated_at`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP comment '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci comment='客户信息表';

-- 初始化一个测试客户账号（手机号: 18650567815，密码: 123456）
INSERT INTO `user_info` (`phone`, `password`, `name`, `nickname`) VALUES ('18650567815', '123456', '测试客户', '小测') ON DUPLICATE KEY UPDATE `password` = '123456';

-- 已有库执行 ALTER TABLE 添加 password 列（新建库无需执行）
ALTER TABLE `staff_info` ADD COLUMN `password` VARCHAR(128) DEFAULT NULL comment '登录密码' AFTER `name`;

-- 初始化一个测试员工账号（工号: TEST001，密码: 123456）
INSERT INTO `staff_info` (`emp_id`, `name`, `password`, `job`, `status`) VALUES ('TEST001', '测试员工', '123456', '知识库管理员', 'ON_JOB') ON DUPLICATE KEY UPDATE `password` = '123456';

-- 问答缓存与统一评价
CREATE TABLE IF NOT EXISTS qa_cache_entry (
    id VARCHAR(36) PRIMARY KEY COMMENT '缓存条目ID，UUID',
    fingerprint CHAR(32) NOT NULL COMMENT '问题键及来源问题ID生成的MD5指纹，用于防止重复入库',
    question_key CHAR(32) COMMENT '归一化问题的MD5键，用于精确去重，不代表语义相似度',
    question VARCHAR(500) NOT NULL COMMENT '整理并审核后的标准问题',
    answer MEDIUMTEXT NOT NULL COMMENT '缓存答案正文，审核通过并发布后可直接返回用户',
    sources JSON NOT NULL COMMENT '来源问题ID、会话ID、原始问答及RAG引用快照',
    frequency INT NOT NULL COMMENT '提取时最近7天内该语义问题对应的合格独立会话数',
    quality DOUBLE NOT NULL COMMENT '模型给出的候选答案质量置信度，范围0到1',
    reason TEXT COMMENT '模型整理依据及需要人工核实的事项',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待审核、APPROVED待发布、ACTIVE已发布、REJECTED已拒绝、DISABLED已下线',
    reviewer VARCHAR(128) COMMENT '最近一次人工审核或下线操作的员工账号',
    review_note TEXT COMMENT '最近一次人工审核或下线意见',
    reviewed_at DATETIME COMMENT '最近一次人工审核或下线操作时间',
    expires_at DATETIME COMMENT '人工审批设定的有效期截止时间，过期后不再返回缓存答案',
    index_error VARCHAR(256) COMMENT '最近一次向量发布失败说明，发布成功后清空',
    hits BIGINT NOT NULL DEFAULT 0 COMMENT '累计缓存命中次数',
    activated_at DATETIME COMMENT '首次发布生效时间，用于判断准出观察期',
    disabled_at DATETIME COMMENT '缓存条目下线时间',
    disable_reason VARCHAR(32) COMMENT '下线原因：MANUAL人工、EXPIRED过期、SOURCE_UNVERIFIABLE来源不可核验、KNOWLEDGE_CHANGED知识变化、NEGATIVE_FEEDBACK差评、LOW_HIT_RATE低命中率',
    disable_detail VARCHAR(1000) COMMENT '自动下线的原因详情或统计依据',
    dependencies JSON COMMENT '实际引用的文档及版本ID数组，元素为documentId和versionId；NULL表示尚未解析，保存后不可随当前版本更新',
    vector_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '下线条目的向量是否已清理：0待清理、1已清理',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '候选条目创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '条目最后更新时间',
    UNIQUE KEY uk_qa_cache_fingerprint (fingerprint),
    KEY idx_qa_cache_question (question_key,status),
    KEY idx_qa_cache_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答缓存条目表，保存候选问答、人工审核和发布状态';

-- 已有库执行 ALTER TABLE 添加问答缓存命中与评价相关列（新建库无需执行）
ALTER TABLE `chat_message`
    ADD COLUMN `cache_hit`        TINYINT       NOT NULL DEFAULT 0 comment '本次回答是否命中缓存：0未命中、1命中' AFTER `metadata`,
    ADD COLUMN `helpful`          TINYINT       NULL comment '回答评价：NULL未评价、1有帮助、0无帮助' AFTER `cache_hit`,
    ADD COLUMN `feedback_comment` VARCHAR(1000) NULL comment '用户评价说明' AFTER `helpful`,
    ADD COLUMN `feedback_at`      DATETIME(6)   NULL comment '首次评价时间' AFTER `feedback_comment`;
