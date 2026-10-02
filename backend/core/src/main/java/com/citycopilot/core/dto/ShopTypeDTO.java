package com.citycopilot.core.dto;

/** 公开分类字典；与原接口一致，不包含持久化审计时间。 */
public record ShopTypeDTO(Long id, String name, String icon, Integer sort) {}
