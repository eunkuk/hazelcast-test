package kr.com.hazelcasttest.controller;

import kr.com.hazelcasttest.model.DistributedEvent;
import kr.com.hazelcasttest.service.CallbackService;
import kr.com.hazelcasttest.service.EventConsumerService;
import kr.com.hazelcasttest.service.EventProducerService;
import kr.com.hazelcasttest.service.ServerIdentificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 이벤트 컨트롤러
 * 이벤트 생성 및 처리를 위한 REST API를 제공합니다.
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventProducerService eventProducerService;
    private final EventConsumerService eventConsumerService;
    private final ServerIdentificationService serverIdentificationService;
    private final CallbackService callbackService;

    /**
     * 서버 정보 조회
     *
     * @return 서버 정보
     */
    @GetMapping("/server-info")
    public ResponseEntity<Map<String, String>> getServerInfo() {
        Map<String, String> info = new HashMap<>();
        info.put("serverId", serverIdentificationService.getServerId());
        info.put("serverHost", serverIdentificationService.getServerHost());
        info.put("serverInfo", serverIdentificationService.getServerInfo());
        return ResponseEntity.ok(info);
    }

    /**
     * 이벤트 생성 (기본 버전)
     *
     * @param eventType 이벤트 유형
     * @param payload   이벤트 데이터
     * @return 생성된 이벤트
     */
    @PostMapping
    public ResponseEntity<DistributedEvent> createEvent(
            @RequestParam String eventType,
            @RequestParam String payload) {
        log.info("이벤트 생성 요청: 유형={}, 데이터={}", eventType, payload);
        DistributedEvent event = eventProducerService.produceEvent(eventType, payload);
        return ResponseEntity.ok(event);
    }

    /**
     * 이벤트 생성 (확장 버전)
     *
     * @param eventType 이벤트 유형
     * @param payload 이벤트 데이터
     * @param notificationUrl 알림 URL
     * @param callbackHeaders 콜백 헤더
     * @param messageGroupId 메시지 그룹 ID
     * @param deduplicationId 중복 제거 ID
     * @param maxRetries 최대 재시도 횟수
     * @param retryDelayMs 재시도 지연 시간 (밀리초)
     * @param visibilityTimeoutMs 가시성 타임아웃 (밀리초)
     * @return 생성된 이벤트
     */
    @PostMapping("/advanced")
    public ResponseEntity<DistributedEvent> createAdvancedEvent(
            @RequestParam String eventType,
            @RequestParam String payload,
            @RequestParam(required = false) String notificationUrl,
            @RequestParam(required = false) Map<String, String> callbackHeaders,
            @RequestParam(required = false) String messageGroupId,
            @RequestParam(required = false) String deduplicationId,
            @RequestParam(required = false, defaultValue = "3") int maxRetries,
            @RequestParam(required = false, defaultValue = "5000") long retryDelayMs,
            @RequestParam(required = false, defaultValue = "30000") long visibilityTimeoutMs) {

        log.info("고급 이벤트 생성 요청: 유형={}, 알림URL={}, 중복제거ID={}",
                eventType, notificationUrl, deduplicationId);

        DistributedEvent event = eventProducerService.produceEvent(
                eventType, payload, notificationUrl, callbackHeaders,
                messageGroupId, deduplicationId, maxRetries,
                retryDelayMs, visibilityTimeoutMs);

        return ResponseEntity.ok(event);
    }

    /**
     * 이벤트 상태 조회
     *
     * @param eventId 이벤트 ID
     * @return 이벤트 상태
     */
    @GetMapping("/{eventId}")
    public ResponseEntity<DistributedEvent> getEventStatus(@PathVariable String eventId) {
        DistributedEvent event = eventProducerService.getEventStatus(eventId);
        if (event != null) {
            return ResponseEntity.ok(event);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 큐 상태 조회
     *
     * @return 큐 상태 정보
     */
    @GetMapping("/queue-info")
    public ResponseEntity<Map<String, Object>> getQueueInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("queueSize", eventProducerService.getQueueSize());
        info.put("processedEvents", eventConsumerService.getProcessedEventCount());
        info.put("failedEvents", eventConsumerService.getFailedEventCount());
        info.put("callbackFailedEvents", eventConsumerService.getCallbackFailedCount());
        info.put("callbackRetryQueueSize", callbackService.getCallbackRetryQueueSize());
        info.put("statistics", eventConsumerService.getStatistics());
        return ResponseEntity.ok(info);
    }

    /**
     * 콜백 실패 이벤트 재시도
     *
     * @param eventId 이벤트 ID
     * @return 재시도 결과
     */
    @PostMapping("/retry-callback/{eventId}")
    public ResponseEntity<Map<String, Object>> retryCallback(@PathVariable String eventId) {
        Map<String, Object> result = new HashMap<>();

        boolean success = callbackService.retryCallback(eventId);

        if (success) {
            result.put("success", true);
            result.put("message", "콜백 재시도가 예약되었습니다.");
            result.put("eventId", eventId);
            return ResponseEntity.ok(result);
        } else {
            result.put("success", false);
            result.put("message", "콜백 재시도 실패: 이벤트가 없거나 콜백 실패 상태가 아닙니다.");
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 성능 테스트 - 대량의 이벤트 생성
     *
     * @param count     생성할 이벤트 수
     * @param eventType 이벤트 유형
     * @return 테스트 결과
     */
    @PostMapping("/performance-test")
    public ResponseEntity<Map<String, Object>> performanceTest(
            @RequestParam(defaultValue = "1000") int count,
            @RequestParam(defaultValue = "NOTIFICATION") String eventType) {

        log.info("성능 테스트 시작: 이벤트 수={}, 유형={}", count, eventType);

        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);

        // 병렬로 이벤트 생성을 위한 스레드 풀
        ExecutorService executorService = Executors.newFixedThreadPool(10);

        try {
            // CompletableFuture를 사용하여 병렬로 이벤트 생성
            CompletableFuture<?>[] futures = new CompletableFuture[count];

            for (int i = 0; i < count; i++) {
                final int index = i;
                futures[i] = CompletableFuture.runAsync(() -> {
                    try {
                        String payload = String.format("{\"index\": %d, \"message\": \"테스트 메시지\", \"timestamp\": %d}",
                                index, System.currentTimeMillis());

                        eventProducerService.produceEvent(eventType, payload);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        log.error("이벤트 생성 중 오류: {}", e.getMessage());
                    }
                }, executorService);
            }

            // 모든 이벤트 생성 완료 대기
            CompletableFuture.allOf(futures).join();

        } finally {
            executorService.shutdown();
            try {
                executorService.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // 결과 생성
        Map<String, Object> result = new HashMap<>();
        result.put("requestedCount", count);
        result.put("successCount", successCount.get());
        result.put("durationMs", duration);
        result.put("eventsPerSecond", count * 1000.0 / duration);
        result.put("queueSize", eventProducerService.getQueueSize());
        result.put("processedEvents", eventConsumerService.getProcessedEventCount());
        result.put("failedEvents", eventConsumerService.getFailedEventCount());

        log.info("성능 테스트 완료: 소요 시간={}ms, 초당 이벤트={}",
                duration, count * 1000.0 / duration);

        return ResponseEntity.ok(result);
    }
}
