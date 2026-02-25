package com.agentplatform.common.repository;

import com.agentplatform.common.model.Grant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface GrantRepository extends JpaRepository<Grant, String> {

    @Query("SELECT g FROM Grant g WHERE g.granteeTid = :granteeTid AND g.status = 'active' " +
           "AND (g.expiresAt IS NULL OR g.expiresAt > CURRENT_TIMESTAMP)")
    List<Grant> findActiveByGranteeTid(@Param("granteeTid") String granteeTid);

    @Query("SELECT g FROM Grant g WHERE g.grantorTid = :grantorTid AND g.granteeTid = :granteeTid " +
           "AND g.status = 'active' AND (g.expiresAt IS NULL OR g.expiresAt > CURRENT_TIMESTAMP)")
    List<Grant> findActiveGrants(@Param("grantorTid") String grantorTid,
                                  @Param("granteeTid") String granteeTid);

    @Query(value = "SELECT * FROM grant_record g WHERE g.grantor_tid = :ownerTid AND g.grantee_tid = :actorTid " +
           "AND g.status = 'active' AND (g.expires_at IS NULL OR g.expires_at > NOW()) " +
           "AND JSON_CONTAINS(g.tools, JSON_QUOTE(:toolId))", nativeQuery = true)
    Optional<Grant> findActiveGrant(@Param("ownerTid") String ownerTid,
                                     @Param("actorTid") String actorTid,
                                     @Param("toolId") String toolId);

    List<Grant> findByGrantorTid(String grantorTid);

    List<Grant> findByGranteeTid(String granteeTid);

    @Modifying
    @Query("UPDATE Grant g SET g.status = 'revoked', g.revokedAt = :revokedAt, " +
           "g.revokeReason = :reason, g.updatedAt = :revokedAt WHERE g.grantId = :grantId")
    void revoke(@Param("grantId") String grantId,
                @Param("reason") String reason,
                @Param("revokedAt") Instant revokedAt);
}
