package com.chaconez.knowengine.auth.dto;

import lombok.Data;

/**
 * 员工登录请求参数
 */
@Data
public class StaffLoginDTO {

    /**
     * 工号
     */
    private String empId;

    /**
     * 密码
     */
    private String password;
}
