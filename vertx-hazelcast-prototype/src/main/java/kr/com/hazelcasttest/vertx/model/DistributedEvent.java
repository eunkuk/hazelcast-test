package kr.com.hazelcasttest.vertx.model;

import io.vertx.core.json.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
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

    /**
     * JsonObject로 변환
     *
     * @return 이벤트를 나타내는 JsonObject
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject()
                .put("eventId", eventId)
                .put("eventType", eventType)
                .put("payload", payload)
                .put("createdAt", createdAt.toString())
                .put("status", status.name());

        if (processedAt != null) {
            json.put("processedAt", processedAt.toString());
        }

        json.put("sourceServerId", sourceServerId);

        if (processingServerId != null) {
            json.put("processingServerId", processingServerId);
        }

        json.put("retryCount", retryCount)
            .put("maxRetries", maxRetries)
            .put("retryDelayMs", retryDelayMs);

        if (notificationUrl != null) {
            json.put("notificationUrl", notificationUrl);
        }

        if (!callbackHeaders.isEmpty()) {
            JsonObject headersJson = new JsonObject();
            callbackHeaders.forEach(headersJson::put);
            json.put("callbackHeaders", headersJson);
        }

        json.put("visibilityTimeoutMs", visibilityTimeoutMs);

        if (visibleAfter != null) {
            json.put("visibleAfter", visibleAfter.toString());
        }

        if (messageGroupId != null) {
            json.put("messageGroupId", messageGroupId);
        }

        if (deduplicationId != null) {
            json.put("deduplicationId", deduplicationId);
        }

        json.put("callbackRetryCount", callbackRetryCount)
            .put("callbackMaxRetries", callbackMaxRetries)
            .put("callbackRetryDelayMs", callbackRetryDelayMs);

        if (lastCallbackAttemptAt != null) {
            json.put("lastCallbackAttemptAt", lastCallbackAttemptAt.toString());
        }

        return json;
    }

    /**
     * JsonObject에서 생성
     *
     * @param json 이벤트를 나타내는 JsonObject
     * @return DistributedEvent 인스턴스
     */
    public static DistributedEvent fromJson(JsonObject json) {
        DistributedEvent event = new DistributedEvent();

        event.setEventId(json.getString("eventId"));
        event.setEventType(json.getString("eventType"));
        event.setPayload(json.getString("payload"));

        String createdAtStr = json.getString("createdAt");
        if (createdAtStr != null) {
            event.setCreatedAt(LocalDateTime.parse(createdAtStr));
        }

        String processedAtStr = json.getString("processedAt");
        if (processedAtStr != null) {
            event.setProcessedAt(LocalDateTime.parse(processedAtStr));
        }

        event.setSourceServerId(json.getString("sourceServerId"));
        event.setProcessingServerId(json.getString("processingServerId"));

        String statusStr = json.getString("status");
        if (statusStr != null) {
            event.setStatus(EventStatus.valueOf(statusStr));
        }

        event.setRetryCount(json.getInteger("retryCount", 0));
        event.setMaxRetries(json.getInteger("maxRetries", 3));
        event.setRetryDelayMs(json.getLong("retryDelayMs", 5000L));
        event.setNotificationUrl(json.getString("notificationUrl"));

        JsonObject headersJson = json.getJsonObject("callbackHeaders");
        if (headersJson != null) {
            Map<String, String> headers = new HashMap<>();
            for (String key : headersJson.fieldNames()) {
                headers.put(key, headersJson.getString(key));
            }
            event.setCallbackHeaders(headers);
        }

        event.setVisibilityTimeoutMs(json.getLong("visibilityTimeoutMs", 30000L));

        String visibleAfterStr = json.getString("visibleAfter");
        if (visibleAfterStr != null) {
            event.setVisibleAfter(Instant.parse(visibleAfterStr));
        }

        event.setMessageGroupId(json.getString("messageGroupId"));
        event.setDeduplicationId(json.getString("deduplicationId"));
        event.setCallbackRetryCount(json.getInteger("callbackRetryCount", 0));
        event.setCallbackMaxRetries(json.getInteger("callbackMaxRetries", 3));
        event.setCallbackRetryDelayMs(json.getLong("callbackRetryDelayMs", 5000L));

        String lastCallbackAttemptAtStr = json.getString("lastCallbackAttemptAt");
        if (lastCallbackAttemptAtStr != null) {
            event.setLastCallbackAttemptAt(LocalDateTime.parse(lastCallbackAttemptAtStr));
        }

        return event;
    }
}
