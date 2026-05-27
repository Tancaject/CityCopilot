package com.citycopilot.core.service.discovery;

import com.citycopilot.core.dto.ShopDiscoveryItemDTO;

import java.util.List;

@FunctionalInterface
public interface ShopDiscoveryProvider {
    List<ShopDiscoveryItemDTO> discover(ShopDiscoveryQuery query);
}
