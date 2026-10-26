package com.chaconez.knowengine.demo;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 演示数据初始化接口。
 * <p>
 * 用途：一键把演示用的业务数据（车型库、我的车辆、订单）写入业务库，
 * 使「选择车型」卡片、名下唯一车辆自动绑定、text-to-sql 结构化查询等演示路径可用。
 * <p>
 * 安全约束：
 * <ul>
 *   <li>仅员工账号可调用（文档管理同级别权限）</li>
 *   <li>只执行 classpath 下固定的 {@code demo/demo-data.sql}，不接收任何外部 SQL 参数</li>
 *   <li>脚本自身幂等（INSERT ... ON DUPLICATE KEY UPDATE），可重复调用</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/demo")
public class DemoDataController {

    private static final Logger log = LoggerFactory.getLogger(DemoDataController.class);

    private static final String SCRIPT_PATH = "demo/demo-data.sql";

    private final DataSource dataSource;

    public DemoDataController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 导入演示业务数据，返回导入后的实际数据作为校验。
     */
    @PostMapping("/init-business-data")
    public Map<String, Object> initBusinessData() {
        requireStaff();

        ClassPathResource script = new ClassPathResource(SCRIPT_PATH);
        if (!script.exists()) {
            throw new IllegalStateException("演示数据脚本缺失: " + SCRIPT_PATH);
        }

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(script, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("演示业务数据导入失败", e);
            throw new IllegalStateException("演示业务数据导入失败: " + e.getMessage(), e);
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("carInfo", jdbc.queryForList(
                "SELECT info_id, full_name, guide_price, range_km FROM car_info ORDER BY id"));
        result.put("myCar", jdbc.queryForList(
                "SELECT car_id, user_id, car_info_id, plate_number, full_name FROM my_car ORDER BY id"));
        result.put("carOrder", jdbc.queryForList(
                "SELECT order_no, order_type, order_status, actual_amount FROM car_order ORDER BY id"));
        log.info("演示业务数据已导入: 车型 {} 条, 我的车辆 {} 条, 订单 {} 条",
                ((java.util.List<?>) result.get("carInfo")).size(),
                ((java.util.List<?>) result.get("myCar")).size(),
                ((java.util.List<?>) result.get("carOrder")).size());
        return result;
    }

    /**
     * 给演示客户追加第二台车。
     * <p>
     * 用途：名下只有一台车时系统会自动绑定、不弹卡片；追加第二台后，
     * 售后/技术类问题必然返回「请先选择车辆」卡片，用于演示车辆选择交互。
     * 与 {@link #disableMultiCar()} 配合可来回切换演示场景。
     */
    @PostMapping("/enable-multi-car")
    public Map<String, Object> enableMultiCar() {
        requireStaff();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int inserted = jdbc.update("""
                INSERT INTO `my_car`
                (`car_id`, `user_id`, `car_info_id`, `nickname`, `full_name`, `order_id`, `plate_number`,
                 `color`, `vin`, `engine_number`, `purchase_date`, `purchase_price`, `mileage`,
                 `register_date`, `insurance_expire_date`, `inspection_expire_date`, `remark`)
                SELECT 'MYCAR-0002', u.`id`, 'CAR-S3-2025', '小蓝', '星驰 S3 2025款 智驾版', 'ORD-2025-0002', '川A·66S3L',
                       '极夜黑', 'LXS3A2025DEMO002', 'EN2025DEMO002', '2025-06-10', 249000.00, 5300,
                       '2025-06-12', '2027-06-09', '2027-06-30', '演示第二台车，用于触发车辆选择卡片。'
                FROM `user_info` u
                WHERE u.`phone` = '18650567815'
                  AND NOT EXISTS (SELECT 1 FROM `my_car` c WHERE c.`car_id` = 'MYCAR-0002')
                """);
        return carSummary(jdbc, inserted);
    }

    /**
     * 移除第二台车，恢复「名下唯一车辆自动绑定」的演示场景。
     */
    @PostMapping("/disable-multi-car")
    public Map<String, Object> disableMultiCar() {
        requireStaff();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int deleted = jdbc.update("DELETE FROM `my_car` WHERE `car_id` = 'MYCAR-0002'");
        return carSummary(jdbc, deleted);
    }

    private Map<String, Object> carSummary(JdbcTemplate jdbc, int affected) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("affected", affected);
        result.put("myCar", jdbc.queryForList("""
                SELECT c.`car_id`, c.`plate_number`, c.`full_name`
                FROM `my_car` c
                JOIN `user_info` u ON u.`id` = c.`user_id`
                WHERE u.`phone` = '18650567815'
                ORDER BY c.`id`
                """));
        return result;
    }

    private void requireStaff() {
        String loginId = StpUtil.getLoginIdAsString();
        if (!loginId.startsWith("staff_")) {
            throw new NotLoginException("需要员工登录", NotLoginException.NOT_TOKEN, null);
        }
    }
}
