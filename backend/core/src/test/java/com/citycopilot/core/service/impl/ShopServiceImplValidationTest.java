package com.citycopilot.core.service.impl;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.entity.ShopType;
import com.citycopilot.core.service.IShopTypeService;
import com.citycopilot.core.service.OperationLogService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopServiceImplValidationTest {

    @Test
    void verifiedCreateRequiresValidCoordinatesBeforeDatabaseWrite() {
        ShopServiceImpl service = new ShopServiceImpl();
        IShopTypeService shopTypeService = mock(IShopTypeService.class);
        when(shopTypeService.getById(7L)).thenReturn(new ShopType().setId(7L));
        ReflectionTestUtils.setField(service, "shopTypeService", shopTypeService);
        Shop request = new Shop()
                .setName("Verified without point")
                .setTypeId(7L)
                .setGeoStatus("verified");

        Result result = ReflectionTestUtils.invokeMethod(
                service,
                "validateShopPayload",
                request,
                true
        );

        assertFalse(result.getSuccess());
        assertEquals("SHOP_GEO_INVALID", result.getCode());
    }

    @Test
    void geoUpdateRejectsUnknownStatusBeforeDatabaseWrite() {
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "operationLogService", mock(OperationLogService.class));
        ShopGeoUpdateDTO request = new ShopGeoUpdateDTO();
        request.setId(1L);
        request.setX(120D);
        request.setY(30D);
        request.setGeoStatus("trusted");

        Result result = service.updateShopGeo(request);

        assertFalse(result.getSuccess());
        assertEquals("BAD_REQUEST", result.getCode());
    }
}
