package com.jake.realtimeapi.users.worker;

import com.jake.realtimeapi.users.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "users.worker.enabled", havingValue = "true")
/**
 * Users 도메인 Worker 예시.
 *
 * <p>이 클래스는 도메인 worker 레이어를 어떻게 구성하는지 보여주기 위한 샘플입니다.
 * 기능적으로는 주기적으로 유저 수를 로그로 남깁니다.
 *
 * <p>기본값은 비활성화이며, 아래 설정이 있을 때만 동작합니다.
 * users.worker.enabled=true
 */
public class UserSummaryWorker {

    private static final Logger log = LoggerFactory.getLogger(UserSummaryWorker.class);

    private final UserRepository userRepository;

    public UserSummaryWorker(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Scheduled(fixedDelayString = "${users.worker.summary-delay-ms:60000}")
    /**
     * 고정 지연 스케줄 작업.
     *
     * <p>이전 실행이 끝난 시점부터 fixedDelay 이후 다시 실행됩니다.
     * 현재는 단순 로그만 남기지만, 이후 통계 집계/정리 배치로 확장 가능합니다.
     */
    public void logUserCount() {
        log.info("users.summary totalCount={}", userRepository.count());
    }
}
