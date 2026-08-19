package com.ticketrush.shared

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

// BaseEntity(공통 created_at/updated_at) 검증만을 위한 테스트 전용 엔티티.
// db/testmigration/R__base_entity_test_probe.sql 테이블에 매핑된다.
@Entity
@Table(name = "base_entity_test_probe")
class BaseEntityTestProbe(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseEntity() {
    // updatedAt 변경을 관찰하려면 바뀔 무언가가 필요하다. 실제 도메인 의미는 없다.
    var note: String? = null
}

interface BaseEntityTestProbeRepository : JpaRepository<BaseEntityTestProbe, Long>
