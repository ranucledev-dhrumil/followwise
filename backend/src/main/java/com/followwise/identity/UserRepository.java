package com.followwise.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.failedAttempts = u.failedAttempts + 1, u.lockedUntil = CASE WHEN (u.failedAttempts + 1 >= 5) THEN :lockUntil ELSE u.lockedUntil END, u.updatedAt = :now WHERE u.id = :id")
    int recordFailedAttempt(@Param("id") UUID id, @Param("lockUntil") Instant lockUntil, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.failedAttempts = 0, u.lockedUntil = NULL, u.updatedAt = :now WHERE u.id = :id")
    int resetFailedAttempts(@Param("id") UUID id, @Param("now") Instant now);
}
