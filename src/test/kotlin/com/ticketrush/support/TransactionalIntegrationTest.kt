package com.ticketrush.support

import org.springframework.transaction.annotation.Transactional

// 단일 스레드로 도는 일반 통합 테스트용. 테스트 메서드마다 트랜잭션을 열고 끝나면
// 롤백해서 테스트끼리 저장한 데이터가 안 섞이게 한다. 여러 스레드가 동시에 DB에
// 접근하는 동시성 테스트는 이 롤백 방식과 맞지 않아 IntegrationTest를 직접 상속해야 한다.
@Transactional
abstract class TransactionalIntegrationTest : IntegrationTest()
