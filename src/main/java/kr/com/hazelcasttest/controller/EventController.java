package kr.com.hazelcasttest.controller;

import kr.com.hazelcasttest.model.DistributedEvent;
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
     * 이벤트 생성
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
        info.put("statistics", eventConsumerService.getStatistics());
        return ResponseEntity.ok(info);
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
