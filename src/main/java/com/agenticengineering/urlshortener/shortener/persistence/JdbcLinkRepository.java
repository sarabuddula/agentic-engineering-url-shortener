package com.agenticengineering.urlshortener.shortener.persistence;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import com.agenticengineering.urlshortener.shortener.domain.DuplicateRequestIdentityException;
import com.agenticengineering.urlshortener.shortener.domain.DuplicateShortCodeException;
import com.agenticengineering.urlshortener.shortener.domain.LinkRepository;
import com.agenticengineering.urlshortener.shortener.domain.NewLink;
import com.agenticengineering.urlshortener.shortener.domain.ShortLink;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * SHORT_LINK repository on Spring JDBC (T035; ADR-003, persistence amendment 2026-10-21). Uniqueness comes from the
 * schema's named constraints; a violation is reported as the matching domain exception. Insert and select only: no
 * update or delete, so a code is never reassigned (FR-004).
 */
@Repository
public class JdbcLinkRepository implements LinkRepository {

    private static final String COLUMNS = "ID, SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT";

    private final JdbcTemplate jdbc;

    public JdbcLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ShortLink insert(NewLink link) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL,"
                        + " REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT) VALUES (?, ?, ?, ?, ?, ?)", new String[] {"ID"});
                statement.setString(1, link.code().value());
                statement.setString(2, link.target().value());
                statement.setString(3, link.requestIdentity());
                statement.setString(4, link.payloadHash());
                statement.setTimestamp(5, Timestamp.from(link.createdAt()));
                statement.setTimestamp(6, Timestamp.from(link.expiresAt()));
                return statement;
            }, key);
        } catch (DuplicateKeyException e) {
            String detail = String.valueOf(e.getMostSpecificCause().getMessage());
            if (detail.contains("UK_SHORT_LINK_REQUEST_IDENTITY")) {
                throw new DuplicateRequestIdentityException(link.requestIdentity());
            }
            if (detail.contains("UK_SHORT_LINK_CODE")) {
                throw new DuplicateShortCodeException(link.code());
            }
            throw e;
        }
        return new ShortLink(key.getKey().longValue(), link.code(), link.target(), link.requestIdentity(),
                link.payloadHash(), link.createdAt(), link.expiresAt());
    }

    @Override
    public Optional<ShortLink> findByCode(ShortCode code) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM SHORT_LINK WHERE SHORT_CODE = ?", JdbcLinkRepository::map, code.value()));
    }

    @Override
    public Optional<ShortLink> findByRequestIdentity(String requestIdentity) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM SHORT_LINK WHERE REQUEST_IDENTITY = ?", JdbcLinkRepository::map,
                requestIdentity));
    }

    private static Optional<ShortLink> first(List<ShortLink> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static ShortLink map(ResultSet rs, int row) throws SQLException {
        return new ShortLink(rs.getLong("ID"), ShortCode.of(rs.getString("SHORT_CODE")), TargetUrl.of(rs.getString("TARGET_URL")),
                rs.getString("REQUEST_IDENTITY"), rs.getString("PAYLOAD_HASH"),
                rs.getObject("CREATED_AT", OffsetDateTime.class).toInstant(),
                rs.getObject("EXPIRES_AT", OffsetDateTime.class).toInstant());
    }
}
