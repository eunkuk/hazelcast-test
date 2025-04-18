package kr.com.hazelcasttest.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
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
     * 이벤트 처리 상태 열거형
     */
    public enum EventStatus {
        CREATED,    // 생성됨
        QUEUED,     // 큐에 추가됨
        PROCESSING, // 처리 중
        COMPLETED,  // 처리 완료
        FAILED      // 처리 실패
    }
}
