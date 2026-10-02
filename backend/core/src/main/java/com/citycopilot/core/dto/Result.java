package com.citycopilot.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Core 公共 HTTP 响应包装，统一表达成功状态、错误码、数据和分页总数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    /** 请求是否成功。 */
    private Boolean success;
    /** 失败时供客户端分支判断的稳定错误码。 */
    private String code;
    /** 失败时供用户阅读的错误说明。 */
    private String errorMsg;
    /** 成功时的业务数据。 */
    private Object data;
    /** 列表分页场景的总记录数。 */
    private Long total;

    /** @return 不携带业务数据的成功响应 */
    public static Result ok(){
        return new Result(true, null, null, null, null);
    }
    /**
     * @param data 业务数据
     * @return 成功响应
     */
    public static Result ok(Object data){
        return new Result(true, null, null, data, null);
    }
    /**
     * @param data 当前页数据
     * @param total 总记录数
     * @return 分页成功响应
     */
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, null, data, total);
    }
    /**
     * @param errorMsg 错误说明
     * @return 使用通用错误码的失败响应
     */
    public static Result fail(String errorMsg){
        return fail("COMMON_ERROR", errorMsg);
    }
    /**
     * @param code 稳定错误码
     * @param errorMsg 错误说明
     * @return 失败响应
     */
    public static Result fail(String code, String errorMsg){
        return new Result(false, code, errorMsg, null, null);
    }
}
