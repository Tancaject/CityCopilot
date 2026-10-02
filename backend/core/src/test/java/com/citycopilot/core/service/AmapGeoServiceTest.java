package com.citycopilot.core.service;

import com.citycopilot.core.config.AmapProperties;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.AmapPoiDTO;
import com.citycopilot.core.dto.Result;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AmapGeoServiceTest {

    @Test
    void searchAroundPoiRequestsExtendedFieldsAndMapsRichPoiData() {
        AmapGeoService service = new AmapGeoService(new RestTemplateBuilder());
        AmapProperties amapProperties = new AmapProperties();
        amapProperties.setWebServiceKey("test-key");
        GeoProperties geoProperties = new GeoProperties();
        geoProperties.setNearbyRadiusMeters(5000D);

        ReflectionTestUtils.setField(service, "amapProperties", amapProperties);
        ReflectionTestUtils.setField(service, "geoProperties", geoProperties);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", mock(StringRedisTemplate.class));

        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertTrue(request.getURI().getRawQuery().contains("extensions=all")))
                .andRespond(withSuccess("""
                        {
                          "status": "1",
                          "pois": [
                            {
                              "id": "B001",
                              "name": "瑞幸咖啡",
                              "type": "餐饮服务;咖啡厅",
                              "typecode": "050500",
                              "address": "学林路1号",
                              "tel": "0731-12345678",
                              "business_area": "青园",
                              "tag": "拿铁,生椰拿铁",
                              "pname": "湖南省",
                              "cityname": "长沙市",
                              "adname": "雨花区",
                              "adcode": "430111",
                              "location": "113.000001,28.200001",
                              "distance": "196",
                              "biz_ext": {
                                "rating": "4.8",
                                "cost": "32.0"
                              },
                              "photos": [
                                {
                                  "title": "门店",
                                  "url": "https://img.example.com/shop.jpg"
                                }
                              ]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        Result result = service.searchAroundPoi(113D, 28.2D, "咖啡", 1, 5);

        assertTrue(result.getSuccess());
        List<AmapPoiDTO> pois = pois(result);
        assertEquals(1, pois.size());
        AmapPoiDTO poi = pois.get(0);
        assertEquals("https://img.example.com/shop.jpg", poi.getImages());
        assertEquals("0731-12345678", poi.getTel());
        assertEquals("青园", poi.getBusinessArea());
        assertEquals("拿铁,生椰拿铁", poi.getTag());
        assertEquals(48, poi.getScore());
        assertEquals(32L, poi.getAvgPrice());
        assertEquals(196D, poi.getDistance());
        server.verify();
    }

    @Test
    void searchAroundPoiDoesNotStringifyEmptyListFields() {
        AmapGeoService service = new AmapGeoService(new RestTemplateBuilder());
        AmapProperties amapProperties = new AmapProperties();
        amapProperties.setWebServiceKey("test-key");
        GeoProperties geoProperties = new GeoProperties();
        geoProperties.setNearbyRadiusMeters(5000D);

        ReflectionTestUtils.setField(service, "amapProperties", amapProperties);
        ReflectionTestUtils.setField(service, "geoProperties", geoProperties);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", mock(StringRedisTemplate.class));

        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertTrue(request.getURI().getRawQuery().contains("extensions=all")))
                .andRespond(withSuccess("""
                        {
                          "status": "1",
                          "pois": [
                            {
                              "id": "B002",
                              "name": "无空字段店",
                              "type": "餐饮服务;中餐厅",
                              "typecode": "050100",
                              "address": [],
                              "tel": [],
                              "business_area": [],
                              "tag": [],
                              "location": "113.000001,28.200001"
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        Result result = service.searchAroundPoi(113D, 28.2D, "餐厅", 1, 5);

        assertTrue(result.getSuccess());
        AmapPoiDTO poi = pois(result).get(0);
        assertEquals(null, poi.getAddress());
        assertEquals(null, poi.getTel());
        assertEquals(null, poi.getBusinessArea());
        assertEquals(null, poi.getTag());
        server.verify();
    }

    @SuppressWarnings("unchecked")
    private List<AmapPoiDTO> pois(Result result) {
        return (List<AmapPoiDTO>) result.getData();
    }
}
