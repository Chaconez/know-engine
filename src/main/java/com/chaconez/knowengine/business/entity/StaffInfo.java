package com.chaconez.knowengine.business.entity;

import com.chaconez.knowengine.business.constant.StaffStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 员工信息表
 */
@Data
@TableName("staff_info")
public class StaffInfo {

    /**
     * 主键id
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 工号
     */
    private String empId;

    /**
     * 姓名
     */
    private String name;

    /**
     * 登录密码
     */
    private String password;

    /**
     * 岗位
     */
    private String job;

    /**
     * 入职时间
     */
    private LocalDate entryTime;

    /**
     * 生日
     */
    private LocalDate birthday;

    /**
     * 学历：junior、undergraduate、master、doctor
     */
    private String educationalBackground;

    /**
     * 主管id
     */
    private Long directorId;

    /**
     * 部门id
     */
    private Long deptId;

    /**
     * 工作职责
     */
    private String duty;

    /**
     * 个性签名
     */
    private String motto;

    /**
     * 头像地址
     */
    private String picUrl;

    /**
     * 状态：在职、已离职
     */
    private StaffStatus status;

    /**
     * 离职时间
     */
    private LocalDate resignationTime;
}
