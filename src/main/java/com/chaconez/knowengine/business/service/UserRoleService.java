package com.chaconez.knowengine.business.service;

import com.chaconez.knowengine.chat.entity.ChatParam;
import com.chaconez.knowengine.rag.constant.RoleEnum;

public interface UserRoleService {

    public RoleEnum getUserRole(ChatParam chatParam);
}
