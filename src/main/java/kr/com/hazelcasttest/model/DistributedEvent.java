package kr.com.hazelcasttest.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 분산 이벤트 모델
 * 다중 서버 환경에서 전달되는 이벤트를 나타내는 클래스입니다.
 * Hazelcast를 통해 전송되므로 Serializable을 구현합니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 이벤트 고유 ID
     */
    @Builder.Default
    private String eventId = UUID.randomUUID().toString();

    /**
     * 이벤트 유형
     */
    private String eventType;

    /**
     * 이벤트 데이터 (JSON 문자열)
     */
    private String payload;

    /**
     * 이벤트 생성 시간
     */
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 이벤트 처리 시간
     */
    private LocalDateTime processedAt;

    /**
     * 이벤트 생성 서버 ID
     */
    private String sourceServerId;

    /**
     * 이벤트 처리 서버 ID
     */
    private String processingServerId;

    /**
     * 이벤트 처리 상태
     */
    @Builder.Default
    private EventStatus status = EventStatus.CREATED;

    /**
     * 재시도 횟수
     */
    @Builder.Default
    private int retryCount = 0;

    /**
     * 최대 재시도 횟수
     */
    @Builder.Default
    private int maxRetries = 3;

    /**
     * 재시도 지연 시간 (밀리초)
     */
    @Builder.Default
    private long retryDelayMs = 5000;

    /**
     * 알림 URL - 이벤트 처리 완료 시 호출할 URL
     */
    private String notificationUrl;

    /**
     * 콜백 요청에 포함할 헤더
     */
    @Builder.Default
    private Map<String, String> callbackHeaders = new HashMap<>();

    /**
     * 가시성 타임아웃 (밀리초) - 이 시간 동안 다른 소비자에게 보이지 않음
     */
    @Builder.Default
    private long visibilityTimeoutMs = 30000;

    /**
     * 다시 가시화되는 시간
     */
    private Instant visibleAfter;

    /**
     * 메시지 그룹 ID - FIFO 처리를 위한 그룹 식별자
     */
    private String messageGroupId;

    /**
     * 중복 제거 ID - 동일한 ID를 가진 메시지는 중복으로 처리되지 않음
     */
    private String deduplicationId;

    /**
     * 콜백 재시도 횟수
     */
    @Builder.Default
    private int callbackRetryCount = 0;

    /**
     * 콜백 최대 재시도 횟수
     */
    @Builder.Default
    private int callbackMaxRetries = 3;

    /**
     * 콜백 재시도 지연 시간 (밀리초)
     */
    @Builder.Default
    private long callbackRetryDelayMs = 5000;

    /**
     * 마지막 콜백 시도 시간
     */
    private LocalDateTime lastCallbackAttemptAt;

    /**
     * 이벤트 처리 상태 열거형
     */
    public enum EventStatus {
        CREATED,           // 생성됨
        QUEUED,            // 큐에 추가됨
        PROCESSING,        // 처리 중
        COMPLETED,         // 처리 완료
        FAILED,            // 처리 실패
        CALLBACK_FAILED    // 콜백 실패
    }
}
