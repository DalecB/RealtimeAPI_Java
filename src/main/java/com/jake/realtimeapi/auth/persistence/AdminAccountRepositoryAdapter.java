package com.jake.realtimeapi.auth.persistence;

import com.jake.realtimeapi.auth.domain.model.AdminAccount;
import com.jake.realtimeapi.auth.domain.repository.AdminAccountRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AdminAccountRepositoryAdapter implements AdminAccountRepository {

    private static final RowMapper<AdminAccount> ROW_MAPPER = (rs, rowNum) -> new AdminAccount(
            rs.getLong("id"),
            rs.getString("external_id"),
            rs.getString("password_hash")
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AdminAccountRepositoryAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AdminAccount> findByExternalId(String externalId) {
        return jdbcTemplate.query(
                "SELECT id, external_id, password_hash FROM users "
                        + "WHERE external_id = :externalId AND password_hash IS NOT NULL",
                new MapSqlParameterSource("externalId", externalId),
                ROW_MAPPER
        ).stream().findFirst();
    }

    @Override
    public Optional<AdminAccount> findByUserId(long userId) {
        return jdbcTemplate.query(
                "SELECT id, external_id, password_hash FROM users "
                        + "WHERE id = :userId AND password_hash IS NOT NULL",
                new MapSqlParameterSource("userId", userId),
                ROW_MAPPER
        ).stream().findFirst();
    }

    @Override
    public boolean createIfAbsent(String externalId, String passwordHash) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO users (external_id, password_hash) VALUES (:externalId, :passwordHash) "
                        + "ON CONFLICT (external_id) DO NOTHING",
                new MapSqlParameterSource()
                        .addValue("externalId", externalId)
                        .addValue("passwordHash", passwordHash)
        );
        return inserted == 1;
    }
}
