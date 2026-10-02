package com.citycopilot.core.community;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Refuses traffic before legacy Redis likes have a verified durable authority. */
@Component
public final class CommunityFactsReadiness implements InitializingBean {
    private final JdbcTemplate jdbc;

    public CommunityFactsReadiness(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        Boolean ready;
        try {
            ready = jdbc.queryForObject("SELECT ready FROM community_facts_cutover WHERE id=1", Boolean.class);
        } catch (DataAccessException e) {
            throw new IllegalStateException("Core community facts cannot start: apply the Core Flyway migrations, "
                    + "then follow docs/community-facts.md to check the legacy likes cutover.", e);
        }
        if (!Boolean.TRUE.equals(ready)) {
            throw new IllegalStateException("Core community facts cannot start: legacy Redis likes are not imported. "
                    + "Keep old writers stopped and follow docs/community-facts.md (--check, then --apply).");
        }
    }
}
