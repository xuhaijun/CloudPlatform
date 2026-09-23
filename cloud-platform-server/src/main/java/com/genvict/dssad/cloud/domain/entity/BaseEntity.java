package com.genvict.dssad.cloud.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 实体公共字段基类。
 *
 * <p>不使用 Spring Data JPA Auditing（{@code @EnableJpaAuditing} + {@code AuditorAware}），
 * 因为本平台的审计主体是「系统/车端/监管平台」而不是登录用户，用生命周期回调更直接、少一层代理。
 * 时间统一以 {@link Instant}（UTC）落库，展示层再按东八区格式化，避免夏令时/时区错乱。
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseEntity {

    /** 创建时间（UTC）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 最后更新时间（UTC）。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
