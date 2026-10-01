package com.study.fileupload.guard;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IpBlockRepository extends JpaRepository<IpBlock, Long> {

    /** 활성 차단: 수동 해제되지 않았고 기간이 남은 것 */
    @Query("select b from IpBlock b where b.clientIp = :ip and b.releasedAt is null "
            + "and b.blockedUntil > :now order by b.blockedUntil desc")
    List<IpBlock> findActive(@Param("ip") String ip, @Param("now") Instant now);

    default Optional<IpBlock> findFirstActive(String ip, Instant now) {
        return findActive(ip, now).stream().findFirst();
    }

    @Query("select b from IpBlock b where b.releasedAt is null and b.blockedUntil > :now "
            + "order by b.blockedAt desc")
    List<IpBlock> findAllActive(@Param("now") Instant now);
}
