package com.chaconez.knowengine.document.util;


import com.chaconez.knowengine.rag.constant.RoleEnum;

public class DocumentPermissionUtils {

    public static String getDocumentPermission(RoleEnum roleEnum) {

        return switch (roleEnum) {
            case VISITOR -> RoleEnum.VISITOR.name();
            case OWNER -> RoleEnum.OWNER.name();
            case CUSTOMER_SERVICE -> RoleEnum.CUSTOMER_SERVICE.name();
        };
    }


    /**
     * 获取文档可访问权限
     * @param roleEnum
     * @return
     */
    public static String[] getDocumentAccessiblePermission(RoleEnum roleEnum) {

        return switch (roleEnum) {
            // 访客权限
            case VISITOR -> new String[]{RoleEnum.VISITOR.name()};
            // 拥有者权限
            case OWNER -> new String[]{RoleEnum.OWNER.name()
                    , RoleEnum.VISITOR.name()};
            // 客服权限
            case CUSTOMER_SERVICE -> new String[]{RoleEnum.VISITOR.name()
                    , RoleEnum.OWNER.name()
                    , RoleEnum.CUSTOMER_SERVICE.name()};
        };
    }
}
