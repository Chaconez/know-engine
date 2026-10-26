// =============================================================================
// KnowEngine 演示数据 —— 图数据库（Neo4j 5.x）
//
// 与 demo/sql/demo-data.sql 中的 car_info 保持一致：
//   CarVersion.infoId  == car_info.info_id（CAR-X5-2025 / CAR-S3-2025 / CAR-M7-2025）
// 这样关系型检索、图检索、向量检索三路数据讲的是同一个"星驰汽车"。
//
// 执行方式（任选其一）：
//   cypher-shell -a bolt://<host>:7687 -u neo4j -p <password> -f demo-graph.cypher
//   Neo4j Browser 中整段粘贴执行
//
// 幂等性：全部使用 MERGE，可反复执行。
// =============================================================================

// -----------------------------------------------------------------------------
// 0. 可选：清空图库（首次演示建议执行，重复演示时按需取消注释）
// -----------------------------------------------------------------------------
// MATCH (n) DETACH DELETE n;

// -----------------------------------------------------------------------------
// 1. 约束与索引
// -----------------------------------------------------------------------------
CREATE CONSTRAINT unique_car_version IF NOT EXISTS
    FOR (v:CarVersion) REQUIRE v.infoId IS UNIQUE;

CREATE INDEX idx_brand_name IF NOT EXISTS
    FOR (b:Brand) ON (b.name);

CREATE INDEX idx_car_model_name IF NOT EXISTS
    FOR (m:CarModel) ON (m.name);

CREATE INDEX idx_part_no IF NOT EXISTS
    FOR (p:Part) ON (p.partNo);


// -----------------------------------------------------------------------------
// 2. 品牌
// -----------------------------------------------------------------------------
MERGE (xc:Brand {name: '星驰'})
  ON CREATE SET xc.englishName = 'XingChi', xc.country = '中国', xc.founded = 2016,
                xc.aliases = ['星驰汽车', 'XingChi Auto'];


// -----------------------------------------------------------------------------
// 3. 车型（对应 car_info.model_name）
// -----------------------------------------------------------------------------
MERGE (x5:CarModel {name: 'X5'})
  ON CREATE SET x5.vehicleType = 'SUV', x5.fuelType = '增程',
                x5.aliases = ['星驰X5', 'XingChi X5'];

MERGE (s3:CarModel {name: 'S3'})
  ON CREATE SET s3.vehicleType = '轿车', s3.fuelType = '纯电',
                s3.aliases = ['星驰S3', 'XingChi S3'];

MERGE (m7:CarModel {name: 'M7'})
  ON CREATE SET m7.vehicleType = 'MPV', m7.fuelType = '插混',
                m7.aliases = ['星驰M7', 'XingChi M7'];


// -----------------------------------------------------------------------------
// 4. 能源类型
// -----------------------------------------------------------------------------
MERGE (erev:EnergyType {name: '增程式'})
  ON CREATE SET erev.aliases = ['EREV', '增程', 'Extended Range Electric Vehicle'];

MERGE (bev:EnergyType {name: '纯电动'})
  ON CREATE SET bev.aliases = ['BEV', '纯电', 'Battery Electric Vehicle'];

MERGE (phev:EnergyType {name: '插电混动'})
  ON CREATE SET phev.aliases = ['PHEV', '插混', 'Plug-in Hybrid Electric Vehicle'];


// -----------------------------------------------------------------------------
// 5. 车系版本（infoId 与 car_info.info_id 对齐）
// -----------------------------------------------------------------------------
MERGE (x5v:CarVersion {infoId: 'CAR-X5-2025'})
  ON CREATE SET x5v.name = '星驰 X5 2025款 尊享四驱版', x5v.modelYear = 2025,
                x5v.version = '尊享四驱版', x5v.seatCount = 5,
                x5v.displacement = 1.5, x5v.motorPower = 250, x5v.rangeKm = 1200,
                x5v.pureElectricRangeKm = 285, x5v.guidePrice = 35.80,
                x5v.dimensions = '4920x1960x1780', x5v.wheelbase = 2980,
                x5v.manufacturer = '星驰汽车', x5v.status = '在售',
                x5v.batteryType = '磷酸铁锂';

MERGE (s3v:CarVersion {infoId: 'CAR-S3-2025'})
  ON CREATE SET s3v.name = '星驰 S3 2025款 智驾版', s3v.modelYear = 2025,
                s3v.version = '智驾版', s3v.seatCount = 5,
                s3v.motorPower = 200, s3v.rangeKm = 705,
                s3v.guidePrice = 22.90,
                s3v.dimensions = '4780x1860x1490', s3v.wheelbase = 2840,
                s3v.manufacturer = '星驰汽车', s3v.status = '在售',
                s3v.batteryType = '三元锂', s3v.supportsSuperCharge = true;

MERGE (m7v:CarVersion {infoId: 'CAR-M7-2025'})
  ON CREATE SET m7v.name = '星驰 M7 2025款 家用七座版', m7v.modelYear = 2025,
                m7v.version = '家用七座版', m7v.seatCount = 7,
                m7v.displacement = 1.5, m7v.motorPower = 180, m7v.rangeKm = 1100,
                m7v.guidePrice = 28.60,
                m7v.dimensions = '5180x1965x1800', m7v.wheelbase = 3080,
                m7v.manufacturer = '星驰汽车', m7v.status = '在售',
                m7v.batteryType = '磷酸铁锂';


// -----------------------------------------------------------------------------
// 6. 配件
// -----------------------------------------------------------------------------
MERGE (p1:Part {partNo: 'P_001'})
  ON CREATE SET p1.name = '米其林轮胎 255/50 R20', p1.category = '轮胎',
                p1.brand = 'Michelin', p1.price = 2100.00;

MERGE (p2:Part {partNo: 'P_002'})
  ON CREATE SET p2.name = '博世前刹车片', p2.category = '制动系统',
                p2.brand = 'Bosch', p2.price = 780.00;

MERGE (p3:Part {partNo: 'P_003'})
  ON CREATE SET p3.name = '空气悬架系统', p3.category = '底盘系统',
                p3.brand = 'Continental', p3.price = 25000.00;

MERGE (p4:Part {partNo: 'P_004'})
  ON CREATE SET p4.name = '激光雷达模组', p4.category = '智能驾驶',
                p4.brand = 'Hesai', p4.price = 6500.00;

MERGE (p5:Part {partNo: 'P_005'})
  ON CREATE SET p5.name = '二排零重力座椅', p5.category = '座椅系统',
                p5.brand = '星驰自研', p5.price = 18000.00;

MERGE (p6:Part {partNo: 'P_006'})
  ON CREATE SET p6.name = '1.5T 增程器总成', p6.category = '动力系统',
                p6.brand = '星驰自研', p6.price = 32000.00;


// -----------------------------------------------------------------------------
// 7. 关系
// -----------------------------------------------------------------------------
MATCH (b:Brand {name: '星驰'}), (m:CarModel {name: 'X5'})   MERGE (b)-[:HAS_MODEL]->(m);
MATCH (b:Brand {name: '星驰'}), (m:CarModel {name: 'S3'})   MERGE (b)-[:HAS_MODEL]->(m);
MATCH (b:Brand {name: '星驰'}), (m:CarModel {name: 'M7'})   MERGE (b)-[:HAS_MODEL]->(m);

MATCH (m:CarModel {name: 'X5'}), (v:CarVersion {infoId: 'CAR-X5-2025'}) MERGE (m)-[:HAS_VERSION]->(v);
MATCH (m:CarModel {name: 'S3'}), (v:CarVersion {infoId: 'CAR-S3-2025'}) MERGE (m)-[:HAS_VERSION]->(v);
MATCH (m:CarModel {name: 'M7'}), (v:CarVersion {infoId: 'CAR-M7-2025'}) MERGE (m)-[:HAS_VERSION]->(v);

MATCH (v:CarVersion {infoId: 'CAR-X5-2025'}), (e:EnergyType {name: '增程式'})   MERGE (v)-[:HAS_ENERGY_TYPE]->(e);
MATCH (v:CarVersion {infoId: 'CAR-S3-2025'}), (e:EnergyType {name: '纯电动'})   MERGE (v)-[:HAS_ENERGY_TYPE]->(e);
MATCH (v:CarVersion {infoId: 'CAR-M7-2025'}), (e:EnergyType {name: '插电混动'}) MERGE (v)-[:HAS_ENERGY_TYPE]->(e);

// X5：轮胎、刹车片、空气悬架、增程器
MATCH (v:CarVersion {infoId: 'CAR-X5-2025'}), (p:Part {partNo: 'P_001'}) MERGE (v)-[:USES_PART]->(p);
MATCH (v:CarVersion {infoId: 'CAR-X5-2025'}), (p:Part {partNo: 'P_002'}) MERGE (v)-[:USES_PART]->(p);
MATCH (v:CarVersion {infoId: 'CAR-X5-2025'}), (p:Part {partNo: 'P_003'}) MERGE (v)-[:USES_PART]->(p);
MATCH (v:CarVersion {infoId: 'CAR-X5-2025'}), (p:Part {partNo: 'P_006'}) MERGE (v)-[:USES_PART]->(p);

// S3：轮胎、刹车片、激光雷达
MATCH (v:CarVersion {infoId: 'CAR-S3-2025'}), (p:Part {partNo: 'P_001'}) MERGE (v)-[:USES_PART]->(p);
MATCH (v:CarVersion {infoId: 'CAR-S3-2025'}), (p:Part {partNo: 'P_002'}) MERGE (v)-[:USES_PART]->(p);
MATCH (v:CarVersion {infoId: 'CAR-S3-2025'}), (p:Part {partNo: 'P_004'}) MERGE (v)-[:USES_PART]->(p);

// M7：轮胎、刹车片、零重力座椅、增程器
MATCH (v:CarVersion {infoId: 'CAR-M7-2025'}), (p:Part {partNo: 'P_001'}) MERGE (v)-[:USES_PART]->(p);
MATCH (v:CarVersion {infoId: 'CAR-M7-2025'}), (p:Part {partNo: 'P_002'}) MERGE (v)-[:USES_PART]->(p);
MATCH (v:CarVersion {infoId: 'CAR-M7-2025'}), (p:Part {partNo: 'P_005'}) MERGE (v)-[:USES_PART]->(p);
MATCH (v:CarVersion {infoId: 'CAR-M7-2025'}), (p:Part {partNo: 'P_006'}) MERGE (v)-[:USES_PART]->(p);


// -----------------------------------------------------------------------------
// 8. 校验
// -----------------------------------------------------------------------------
MATCH (n) RETURN labels(n)[0] AS label, count(n) AS count ORDER BY count DESC;

// 演示用图查询（可在 Neo4j Browser 单独执行，用于讲解图检索能力）
// 纯电车型都有哪些？
//   MATCH (v:CarVersion)-[:HAS_ENERGY_TYPE]->(:EnergyType {name:'纯电动'}) RETURN v.name, v.rangeKm;
// 哪些车型共用同一款轮胎？
//   MATCH (v:CarVersion)-[:USES_PART]->(p:Part {partNo:'P_001'}) RETURN v.name;
// 星驰 X5 的配件清单与价格合计？
//   MATCH (:CarVersion {infoId:'CAR-X5-2025'})-[:USES_PART]->(p:Part) RETURN p.name, p.price;
