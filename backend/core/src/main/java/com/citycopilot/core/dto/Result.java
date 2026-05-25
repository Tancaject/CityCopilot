package com.citycopilot.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;
    private String code;
    private String errorMsg;
    private Object data;
    private Long total;

    public static Result ok(){
        return new Result(true, null, null, null, null);
    }
    public static Result ok(Object data){
        return new Result(true, null, null, data, null);
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, null, data, total);
    }
    public static Result fail(String errorMsg){
        return fail("COMMON_ERROR", errorMsg);
    }
    public static Result fail(String code, String errorMsg){
        return new Result(false, code, errorMsg, null, null);
    }
}
