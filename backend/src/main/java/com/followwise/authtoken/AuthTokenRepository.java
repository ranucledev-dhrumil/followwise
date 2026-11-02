package com.followwise.authtoken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    Optional<AuthToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE AuthToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.type = :type AND t.usedAt IS NULL")
    int invalidateUnusedTokens(@Param("userId") UUID userId, @Param("type") String type, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE AuthToken t SET t.usedAt = :now WHERE t.tokenHash = :tokenHash AND t.type = :type AND t.usedAt IS NULL AND t.expiresAt > :now")
    int markUsedIfValid(@Param("tokenHash") String tokenHash, @Param("type") String type, @Param("now") Instant now);

    @Query("SELECT COUNT(t) FROM AuthToken t WHERE t.userId = :userId AND t.type = :type AND t.createdAt >= :since")
    long countCreatedSince(@Param("userId") UUID userId, @Param("type") String type, @Param("since") Instant since);
}
