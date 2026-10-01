package com.study.fileupload.guard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 반복 위반 IP 일시 차단 기록. 행 자체가 이력이다 (명세 8-2). */
@Entity
@Table(name = "ip_block")
public class IpBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_ip", nullable = false, length = 45)
    private String clientIp;

    @Column(nullable = false)
    private int score;

    @Column(name = "blocked_at", nullable = false)
    private Instant blockedAt;

    @Column(name = "blocked_until", nullable = false)
    private Instant blockedUntil;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by_ip", length = 45)
    private String releasedByIp;

    protected IpBlock() {
    }

    public static IpBlock of(String clientIp, int score, Instant until) {
        IpBlock block = new IpBlock();
        block.clientIp = clientIp;
        block.score = score;
        block.blockedAt = Instant.now();
        block.blockedUntil = until;
        return block;
    }

    public void release(String byIp) {
        this.releasedAt = Instant.now();
        this.releasedByIp = byIp;
    }

    public Long getId() {
        return id;
    }

    public String getClientIp() {
        return clientIp;
    }

    public int getScore() {
        return score;
    }

    public Instant getBlockedAt() {
        return blockedAt;
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
