package kr.com.hazelcasttest.service;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import kr.com.hazelcasttest.model.DistributedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 이벤트 소비 서비스
 * 헤이즐캐스트 큐에서 이벤트를 가져와 처리하는 서비스입니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventConsumerService {

    private final HazelcastInstance hazelcastInstance;
    private final ServerIdentificationService serverIdentificationService;
    private final CallbackService callbackService;

    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger processedEventCount = new AtomicInteger(0);
    private final AtomicInteger failedEventCount = new AtomicInteger(0);
    private final AtomicInteger callbackFailedCount = new AtomicInteger(0);

    // 중복 제거를 위한 처리된 이벤트 ID 캐시
    private final Map<String, Instant> processedDeduplicationIds = new HashMap<>();

    // 콜백 재시도 큐 이름
    private static final String CALLBACK_RETRY_QUEUE = "callbackRetryQueue";

    /**
     * 서비스 초기화 시 이벤트 처리 스레드 시작
     */
    @PostConstruct
    public void init() {
        // 이벤트 처리를 위한 스레드 풀 생성
        executorService = Executors.newFixedThreadPool(6); // 1개 추가 (콜백 재시도용)

        // 지연된 재시도를 위한 스케줄러 생성
        scheduledExecutorService = Executors.newScheduledThreadPool(2);

        // 이벤트 처리 스레드 시작
        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            executorService.submit(() -> processEvents(threadId));
        }

        // 콜백 재시도 처리 스레드 시작
        executorService.submit(this::processCallbackRetries);

        // 중복 제거 캐시 정리를 위한 스케줄러 시작 (1시간마다 실행)
        scheduledExecutorService.scheduleAtFixedRate(this::cleanupDeduplicationCache, 1, 1, TimeUnit.HOURS);

        log.info("이벤트 소비 서비스 시작됨, 서버: {}", serverIdentificationService.getServerId());
    }

    /**
     * 서비스 종료 시 이벤트 처리 스레드 정리
     */
    @PreDestroy
    public void destroy() {
        running.set(false);

        // 이벤트 처리 스레드 풀 종료
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 스케줄러 종료
        scheduledExecutorService.shutdown();
        try {
            if (!scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduledExecutorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("이벤트 소비 서비스 종료됨, 서버: {}", serverIdentificationService.getServerId());
    }

    /**
     * 중복 제거 캐시 정리
     * 24시간 이상 지난 항목 제거
     */
    private void cleanupDeduplicationCache() {
        Instant now = Instant.now();
        Instant threshold = now.minusSeconds(86400); // 24시간

        synchronized (processedDeduplicationIds) {
            processedDeduplicationIds.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
        }

        log.info("중복 제거 캐시 정리 완료, 남은 항목: {}", processedDeduplicationIds.size());
    }

    /**
     * 이벤트 처리 루프
     *
     * @param threadId 스레드 ID
     */
    private void processEvents(int threadId) {
        log.info("이벤트 처리 스레드 시작: {}-{}", serverIdentificationService.getServerId(), threadId);

        IQueue<DistributedEvent> eventQueue = hazelcastInstance.getQueue("eventQueue");
        IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");

        while (running.get()) {
            try {
                // 큐에서 이벤트 가져오기 (최대 3초 대기)
                DistributedEvent event = eventQueue.poll(3, TimeUnit.SECONDS);

                if (event != null) {
                    // 가시성 타임아웃 확인
                    if (event.getVisibleAfter() != null && Instant.now().isBefore(event.getVisibleAfter())) {
                        // 아직 가시성 타임아웃이 지나지 않았으면 다시 큐에 넣음
                        log.debug("이벤트 가시성 타임아웃 미경과: {}, 다시 큐에 추가", event.getEventId());
                        eventQueue.offer(event);
                        continue;
                    }

                    // 중복 제거 ID가 있는 경우 중복 확인
                    if (event.getDeduplicationId() != null && !event.getDeduplicationId().isEmpty()) {
                        synchronized (processedDeduplicationIds) {
                            if (processedDeduplicationIds.containsKey(event.getDeduplicationId())) {
                                log.info("중복 이벤트 무시: {}, 중복 ID: {}",
                                        event.getEventId(), event.getDeduplicationId());
                                continue;
                            }
                        }
                    }

                    try {
                        // 이벤트 처리 시작
                        log.info("이벤트 처리 시작: {}, 스레드: {}-{}", event.getEventId(),
                                serverIdentificationService.getServerId(), threadId);

                        // 이벤트 상태 업데이트
                        event.setStatus(DistributedEvent.EventStatus.PROCESSING);
                        event.setProcessingServerId(serverIdentificationService.getServerId());
                        eventMap.put(event.getEventId(), event);

                        // 이벤트 처리 (실제 비즈니스 로직)
                        processEvent(event);

                        // 이벤트 처리 완료
                        event.setStatus(DistributedEvent.EventStatus.COMPLETED);
                        event.setProcessedAt(LocalDateTime.now());
                        eventMap.put(event.getEventId(), event);

                        // 중복 제거 ID가 있는 경우 처리 기록
                        if (event.getDeduplicationId() != null && !event.getDeduplicationId().isEmpty()) {
                            synchronized (processedDeduplicationIds) {
                                processedDeduplicationIds.put(event.getDeduplicationId(), Instant.now());
                            }
                        }

                        // 알림 URL이 있는 경우 콜백 실행
                        if (event.getNotificationUrl() != null && !event.getNotificationUrl().isEmpty()) {
                            callbackService.executeCallback(event)
                                .thenAccept(success -> {
                                    if (!success) {
                                        log.warn("콜백 실패: {}", event.getEventId());
                                        callbackFailedCount.incrementAndGet();
                                    }
                                });
                        }

                        processedEventCount.incrementAndGet();
                        log.info("이벤트 처리 완료: {}, 스레드: {}-{}", event.getEventId(),
                                serverIdentificationService.getServerId(), threadId);
                    } catch (Exception e) {
                        // 이벤트 처리 실패
                        log.error("이벤트 처리 실패: {}, 스레드: {}-{}", event.getEventId(),
                                serverIdentificationService.getServerId(), threadId, e);

                        event.setStatus(DistributedEvent.EventStatus.FAILED);
                        event.setRetryCount(event.getRetryCount() + 1);
                        eventMap.put(event.getEventId(), event);

                        failedEventCount.incrementAndGet();

                        // 최대 재시도 횟수보다 적게 시도했으면 재시도
                        if (event.getRetryCount() < event.getMaxRetries()) {
                            // 지연 시간이 설정되어 있으면 지연 후 재시도
                            if (event.getRetryDelayMs() > 0) {
                                // 가시성 타임아웃 설정
                                event.setVisibleAfter(Instant.now().plusMillis(event.getRetryDelayMs()));
                                log.info("이벤트 재시도 예약: {}, {}ms 후", event.getEventId(), event.getRetryDelayMs());
                                eventQueue.offer(event);
                            } else {
                                // 지연 없이 바로 재시도
                                eventQueue.offer(event);
                            }
                        } else {
                            log.warn("이벤트 최대 재시도 횟수 초과: {}, 재시도: {}/{}",
                                    event.getEventId(), event.getRetryCount(), event.getMaxRetries());
                        }
                    }
                }
            } catch (InterruptedException e) {
                log.warn("이벤트 처리 스레드 인터럽트: {}-{}", serverIdentificationService.getServerId(), threadId);
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("이벤트 처리 스레드 종료: {}-{}", serverIdentificationService.getServerId(), threadId);
    }

    /**
     * 이벤트 처리 로직
     * 실제 비즈니스 로직을 여기에 구현합니다.
     *
     * @param event 처리할 이벤트
     */
    private void processEvent(DistributedEvent event) throws Exception {
        // 이벤트 유형에 따라 다른 처리 로직 수행
        switch (event.getEventType()) {
            case "NOTIFICATION":
                // 알림 이벤트 처리
                log.info("알림 이벤트 처리: {}", event.getPayload());
                // 처리 시간 시뮬레이션
                Thread.sleep(100);
                break;

            case "DATA_PROCESSING":
                // 데이터 처리 이벤트
                log.info("데이터 처리 이벤트: {}", event.getPayload());
                // 처리 시간 시뮬레이션
                Thread.sleep(500);
                break;

            case "HEAVY_TASK":
                // 무거운 작업 이벤트
                log.info("무거운 작업 이벤트: {}", event.getPayload());
                // 처리 시간 시뮬레이션
                Thread.sleep(1000);
                break;

            default:
                // 기본 이벤트 처리
                log.info("기본 이벤트 처리: {}", event.getPayload());
                Thread.sleep(200);
                break;
        }

        // 랜덤으로 실패 시뮬레이션 (약 5% 확률)
//        if (Math.random() < 0.05) {
//            throw new RuntimeException("이벤트 처리 중 랜덤 오류 발생 (시뮬레이션)");
//        }
    }

    /**
     * 처리된 이벤트 수 조회
     *
     * @return 처리된 이벤트 수
     */
    public int getProcessedEventCount() {
        return processedEventCount.get();
    }

    /**
     * 실패한 이벤트 수 조회
     *
     * @return 실패한 이벤트 수
     */
    public int getFailedEventCount() {
        return failedEventCount.get();
    }

    /**
     * 콜백 재시도 처리 루프
     * 콜백 재시도 큐에서 이벤트를 가져와 콜백을 재시도합니다.
     */
    private void processCallbackRetries() {
        log.info("콜백 재시도 처리 스레드 시작: {}", serverIdentificationService.getServerId());

        IQueue<DistributedEvent> callbackRetryQueue = hazelcastInstance.getQueue(CALLBACK_RETRY_QUEUE);
        IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");

        while (running.get()) {
            try {
                // 큐에서 이벤트 가져오기 (최대 5초 대기)
                DistributedEvent event = callbackRetryQueue.poll(5, TimeUnit.SECONDS);

                if (event != null) {
                    // 지연 시간 확인 (마지막 시도 후 지정된 지연 시간이 지났는지)
                    if (event.getLastCallbackAttemptAt() != null) {
                        LocalDateTime nextAttemptTime = event.getLastCallbackAttemptAt()
                                .plusNanos(event.getCallbackRetryDelayMs() * 1000000);

                        if (LocalDateTime.now().isBefore(nextAttemptTime)) {
                            // 아직 지연 시간이 지나지 않았으면 다시 큐에 넣음
                            callbackRetryQueue.offer(event);
                            continue;
                        }
                    }

                    log.info("콜백 재시도: 이벤트={}, 시도={}/{}",
                            event.getEventId(), event.getCallbackRetryCount(), event.getCallbackMaxRetries());

                    // 콜백 재시도
                    callbackService.executeCallback(event)
                        .thenAccept(success -> {
                            if (success) {
                                log.info("콜백 재시도 성공: 이벤트={}", event.getEventId());
                                // 성공 시 상태는 CallbackService에서 업데이트됨
                            } else {
                                log.warn("콜백 재시도 실패: 이벤트={}", event.getEventId());
                                // 실패 시 상태는 CallbackService에서 업데이트됨
                                callbackFailedCount.incrementAndGet();
                            }
                        });
                }
            } catch (InterruptedException e) {
                log.warn("콜백 재시도 스레드 인터럽트: {}", serverIdentificationService.getServerId());
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("콜백 재시도 처리 중 오류: {}", e.getMessage(), e);
            }
        }

        log.info("콜백 재시도 처리 스레드 종료: {}", serverIdentificationService.getServerId());
    }

    /**
     * 통계 정보 조회
     *
     * @return 통계 정보 문자열
     */
    public String getStatistics() {
        return String.format("서버: %s, 처리된 이벤트: %d, 실패한 이벤트: %d, 콜백 실패: %d",
                serverIdentificationService.getServerId(),
                processedEventCount.get(),
                failedEventCount.get(),
                callbackFailedCount.get());
    }

    /**
     * 콜백 실패 이벤트 수 조회
     *
     * @return 콜백 실패 이벤트 수
     */
    public int getCallbackFailedCount() {
        return callbackFailedCount.get();
    }
}
