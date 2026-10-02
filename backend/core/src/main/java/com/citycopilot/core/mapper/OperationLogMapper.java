package com.citycopilot.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.citycopilot.core.entity.OperationLog;

/** 通过 MyBatis-Plus 写入和查询后台操作审计记录。 */
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
