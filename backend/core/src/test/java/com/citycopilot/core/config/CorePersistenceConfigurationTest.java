package com.citycopilot.core.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.citycopilot.core.mapper.BlogMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CorePersistenceConfigurationTest {
    @Test
    void bootStarterBuildsExistingMappersAndPaginationWithoutConnectingToDatabase() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(MybatisConfig.class, Mappers.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("mybatis-plus.type-aliases-package=com.citycopilot.core.entity")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(BlogMapper.class));
                    var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
                    assertTrue(configuration.hasStatement(BlogMapper.class.getName() + ".insert"));
                    var statement = configuration.getMappedStatement(BlogMapper.class.getName() + ".likedNotes");
                    var bound = statement.getBoundSql(Map.of("userId", 7L, "ids", List.of(11L, 12L)));
                    assertEquals(3, bound.getParameterMappings().size());
                    var pagination = context.getBean(MybatisPlusInterceptor.class).getInterceptors().get(0);
                    assertInstanceOf(PaginationInnerInterceptor.class, pagination);
                    assertTrue(((PaginationInnerInterceptor) pagination)
                            .autoCountSql(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10),
                                    "SELECT id FROM note WHERE user_id=7 ORDER BY id")
                            .toLowerCase(java.util.Locale.ROOT).contains("count"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.citycopilot.core.mapper")
    static class Mappers {}
}
