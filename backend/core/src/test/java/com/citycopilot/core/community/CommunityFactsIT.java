package com.citycopilot.core.community;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.citycopilot.core.config.MybatisConfig;
import com.citycopilot.core.dto.BlogView;
import com.citycopilot.core.dto.ScrollResult;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.Blog;
import com.citycopilot.core.mapper.BlogMapper;
import com.citycopilot.core.mapper.FollowMapper;
import com.citycopilot.core.service.IUserService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.service.impl.BlogServiceImpl;
import com.citycopilot.core.service.impl.FollowServiceImpl;
import com.citycopilot.core.utils.UserHolder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** Owns only a UUID database on an explicitly supplied disposable local MySQL server. No Redis is needed. */
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_MIGRATION_TEST_URL", matches = "jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")
class CommunityFactsIT {
    private static final String SERVER = System.getenv("CITYCOPILOT_MIGRATION_TEST_URL");
    private static final String USER = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_USER", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("CITYCOPILOT_MIGRATION_TEST_PASSWORD", "");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL&forceConnectionTimeZoneToSession=true";
    private static final String DATABASE = "cc_community_" + UUID.randomUUID().toString().replace("-", "");
    private static HikariDataSource pool;
    private static JdbcTemplate sql;
    private static BlogServiceImpl blogs;
    private static FollowServiceImpl follows;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void setup() throws Exception {
        try (var admin = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(SERVER + DATABASE + OPTIONS);
        config.setUsername(USER);
        config.setPassword(PASSWORD);
        config.setMaximumPoolSize(8);
        pool = new HikariDataSource(config);
        sql = new JdbcTemplate(pool);
        Flyway.configure().dataSource(pool).locations("filesystem:" + Path.of("../../deploy/mysql/core").toAbsolutePath().normalize())
                .cleanDisabled(true).load().migrate();
        MybatisConfiguration mybatis = new MybatisConfiguration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(BlogMapper.class);
        mybatis.addMapper(FollowMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(pool);
        factory.setConfiguration(mybatis);
        factory.setPlugins(new MybatisConfig().mybatisPlusInterceptor());
        SqlSessionTemplate session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        IUserService users = mock(IUserService.class);
        when(users.findUserProfiles(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return ids.stream().map(id -> {
                UserDTO user = new UserDTO();
                user.setId(id);
                user.setNickName("user-" + id);
                return user;
            }).toList();
        });
        BlogServiceImpl target = new BlogServiceImpl(users, mock(OperationLogService.class));
        ReflectionTestUtils.setField(target, "baseMapper", session.getMapper(BlogMapper.class));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(pool);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        blogs = (BlogServiceImpl) proxy.getProxy();
        follows = new FollowServiceImpl();
        ReflectionTestUtils.setField(follows, "baseMapper", session.getMapper(FollowMapper.class));
        transactions = new TransactionTemplate(manager);
    }

    @BeforeEach
    void reset() {
        sql.update("DELETE FROM note");
        sql.update("DELETE FROM user_follow");
        sql.update("DELETE FROM user_account");
        for (long id = 1; id <= 12; id++) {
            sql.update("INSERT INTO user_account(id,phone,nick_name) VALUES(?,?,?)", id, "199" + String.format("%08d", id), "user-" + id);
        }
        login(1);
    }

    @AfterEach
    void clearIdentity() { UserHolder.removeUser(); }

    @AfterAll
    static void cleanup() throws Exception {
        if (pool != null) pool.close();
        try (var admin = DriverManager.getConnection(SERVER + OPTIONS, USER, PASSWORD); var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    void concurrentTogglesKeepDurableRelationsAndCountInOneTransaction() throws Exception {
        note(100, 2);
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> actions = new ArrayList<>();
            for (long id = 1; id <= 12; id++) {
                long actor = id;
                actions.add(() -> {
                    login(actor);
                    try { assertTrue(blogs.likeBlog(100L).getSuccess()); }
                    finally { UserHolder.removeUser(); }
                    return null;
                });
            }
            for (Future<Void> future : workers.invokeAll(actions)) future.get(10, TimeUnit.SECONDS);
            assertEquals(12, count("SELECT COUNT(*) FROM note_like WHERE note_id=100"));
            assertEquals(12, count("SELECT liked FROM note WHERE id=100"));
            for (Future<Void> future : workers.invokeAll(actions)) future.get(10, TimeUnit.SECONDS);
            assertEquals(0, count("SELECT liked FROM note WHERE id=100"));
        } finally { workers.shutdownNow(); }
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            blogs.likeBlog(100L);
            throw new IllegalStateException("force transaction rollback");
        }));
        assertEquals(0, count("SELECT COUNT(*) FROM note_like"));
        blogs.likeBlog(100L);
        assertTrue(((BlogView) blogs.queryBlogById(100L).getData()).isLike());
        assertEquals(1, ((List<?>) blogs.queryBlogLikes(100L).getData()).size());
        blogs.removeById(100L);
        assertEquals(0, count("SELECT COUNT(*) FROM note_like"));
    }

    @Test
    void followsAndFeedUseCurrentDatabaseFactsAcrossTimestampTies() {
        follows.follow(2L, true);
        follows.follow(2L, true);
        follows.follow(3L, true);
        assertEquals(2, count("SELECT COUNT(*) FROM user_follow WHERE user_id=1"));
        for (long id = 200; id < 207; id++) note(id, 2);
        sql.update("UPDATE note SET create_time='2026-01-01 12:00:00'");
        Set<Long> seen = new HashSet<>();
        long cursor = System.currentTimeMillis();
        int offset = 0;
        for (int page = 0; page < 3; page++) {
            ScrollResult result = (ScrollResult) blogs.queryBlogOfFollow(cursor, offset).getData();
            for (Object item : result.getList()) assertTrue(seen.add(((BlogView) item).id()), "No duplicate at a tied timestamp");
            cursor = result.getMinTime();
            offset = result.getOffset();
        }
        assertEquals(7, seen.size());
        assertNull(blogs.queryBlogOfFollow(cursor, offset).getData());
        follows.follow(2L, false);
        follows.follow(2L, false);
        assertNull(blogs.queryBlogOfFollow(System.currentTimeMillis(), 0).getData());
        follows.follow(2L, true);
        assertNotNull(blogs.queryBlogOfFollow(System.currentTimeMillis(), 0).getData());
    }

    @Test
    void publishingCannotInventCountersOrAnotherAuthor() {
        Blog supplied = new Blog().setId(999L).setUserId(12L).setShopId(1L)
                .setTitle("note").setImages("").setContent("content").setLiked(500).setComments(999);
        assertTrue(blogs.saveBlog(supplied).getSuccess());
        Blog stored = blogs.getById(supplied.getId());
        assertEquals(1L, stored.getUserId());
        assertEquals(0, stored.getLiked());
        assertEquals(0, stored.getComments());
        assertNotNull(stored.getCreateTime());
    }

    private static void login(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }

    private static void note(long id, long author) {
        sql.update("INSERT INTO note(id,user_id,shop_id,title,images,content,liked) VALUES(?,?,1,'note','','body',0)", id, author);
    }

    private int count(String statement) { return Objects.requireNonNull(sql.queryForObject(statement, Integer.class)); }
}
