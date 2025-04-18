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

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger processedEventCount = new AtomicInteger(0);
    private final AtomicInteger failedEventCount = new AtomicInteger(0);

    /**
     * 서비스 초기화 시 이벤트 처리 스레드 시작
     */
    @PostConstruct
    public void init() {
        // 이벤트 처리를 위한 스레드 풀 생성
        executorService = Executors.newFixedThreadPool(5);

        // 이벤트 처리 스레드 시작
        for (int i = 0; i < 5; i++) {
            final int threadId = i;
            executorService.submit(() -> processEvents(threadId));
        }

        log.info("이벤트 소비 서비스 시작됨, 서버: {}", serverIdentificationService.getServerId());
    }

    /**
     * 서비스 종료 시 이벤트 처리 스레드 정리
     */
    @PreDestroy
    public void destroy() {
        running.set(false);
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("이벤트 소비 서비스 종료됨, 서버: {}", serverIdentificationService.getServerId());
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

                        // 재시도 횟수가 3회 미만이면 다시 큐에 추가
                        if (event.getRetryCount() < 3) {
                            eventQueue.offer(event);
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
     * 통계 정보 조회
     *
     * @return 통계 정보 문자열
     */
    public String getStatistics() {
        return String.format("서버: %s, 처리된 이벤트: %d, 실패한 이벤트: %d",
                serverIdentificationService.getServerId(),
                processedEventCount.get(),
                failedEventCount.get());
    }
}
