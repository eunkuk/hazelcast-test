package kr.com.hazelcasttest.vertx;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import kr.com.hazelcasttest.vertx.model.DistributedEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 이벤트 소비자 버티클
 * Hazelcast 큐에서 이벤트를 가져와 처리하는 버티클입니다.
 * 워커 버티클로 배포되어 이벤트 루프를 차단하지 않고 블로킹 작업을 수행합니다.
 */
@Slf4j
public class EventConsumerVerticle extends AbstractVerticle {

    private HazelcastInstance hazelcastInstance;
    private String serverId;
    private WebClient webClient;

    // 중복 제거를 위한 처리된 이벤트 ID 캐시
    private final Map<String, Instant> processedDeduplicationIds = new HashMap<>();

    // 콜백 재시도 큐 이름
    private static final String CALLBACK_RETRY_QUEUE = "callbackRetryQueue";

    // 통계 (로컬 카운터)
    private int processedEventCount = 0;
    private int failedEventCount = 0;
    private int callbackFailedCount = 0;

    // 통계 맵 키
    private static final String STATS_MAP_NAME = "eventStatisticsMap";
    private static final String PROCESSED_EVENTS_KEY = "processedEvents";
    private static final String FAILED_EVENTS_KEY = "failedEvents";
    private static final String CALLBACK_FAILED_EVENTS_KEY = "callbackFailedEvents";

    @Override
    public void start(Promise<Void> startPromise) {
        // 주기적으로 Hazelcast 인스턴스를 찾아보는 타이머 설정
        findHazelcastInstance(startPromise);
    }

    private void findHazelcastInstance(Promise<Void> startPromise) {
        // MainVerticle에서 생성된 공유 Hazelcast 인스턴스 사용
        // 모든 Hazelcast 인스턴스를 가져와서 "hazelcast-cluster" 이름을 가진 인스턴스를 찾음
        for (HazelcastInstance instance : com.hazelcast.core.Hazelcast.getAllHazelcastInstances()) {
            if ("hazelcast-cluster".equals(instance.getConfig().getClusterName())) {
                hazelcastInstance = instance;
                log.info("기존 Hazelcast 인스턴스를 찾았습니다: {}", instance.getName());
                initializeVerticle(startPromise);
                return;
            }
        }

        // 인스턴스를 찾지 못한 경우 잠시 후 다시 시도
        log.info("Hazelcast 인스턴스를 찾지 못했습니다. 1초 후 다시 시도합니다.");
        vertx.setTimer(1000, id -> findHazelcastInstance(startPromise));
    }

    private void initializeVerticle(Promise<Void> startPromise) {
        // 통계 맵 초기화
        IMap<String, Long> statsMap = hazelcastInstance.getMap(STATS_MAP_NAME);
        if (!statsMap.containsKey(PROCESSED_EVENTS_KEY)) {
            statsMap.put(PROCESSED_EVENTS_KEY, 0L);
        }
        if (!statsMap.containsKey(FAILED_EVENTS_KEY)) {
            statsMap.put(FAILED_EVENTS_KEY, 0L);
        }
        if (!statsMap.containsKey(CALLBACK_FAILED_EVENTS_KEY)) {
            statsMap.put(CALLBACK_FAILED_EVENTS_KEY, 0L);
        }

        // 서버 ID 생성
        serverId = UUID.randomUUID().toString();
        log.info("이벤트 소비자 버티클 시작: 서버 ID = {}", serverId);

        // 웹 클라이언트 생성 (콜백 요청용)
        webClient = WebClient.create(vertx, new WebClientOptions()
                .setConnectTimeout(10000)
                .setIdleTimeout(10)
                .setMaxPoolSize(50));

        // 이벤트 버스 핸들러 등록
        vertx.eventBus().consumer("event.statistics", this::handleGetStatistics);
        vertx.eventBus().consumer("event.retry.callback", this::handleRetryCallback);

        // 이벤트 처리 타이머 시작
        vertx.setPeriodic(100, id -> processNextEvent());

        // 콜백 재시도 처리 타이머 시작
        vertx.setPeriodic(500, id -> processNextCallbackRetry());

        // 중복 제거 캐시 정리 타이머 시작 (1시간마다)
        vertx.setPeriodic(3600000, id -> cleanupDeduplicationCache());

        startPromise.complete();
    }

    /**
     * 다음 이벤트 처리
     * 큐에서 이벤트를 가져와 처리합니다.
     */
    private void processNextEvent() {
        try {
            IQueue<DistributedEvent> eventQueue = hazelcastInstance.getQueue("eventQueue");
            IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");

            // 큐에서 이벤트 가져오기 (논블로킹)
            DistributedEvent event = eventQueue.poll();

            if (event != null) {
                // 가시성 타임아웃 확인
                if (event.getVisibleAfter() != null && Instant.now().isBefore(event.getVisibleAfter())) {
                    // 아직 가시성 타임아웃이 지나지 않았으면 다시 큐에 넣음
                    log.debug("이벤트 가시성 타임아웃 미경과: {}, 다시 큐에 추가", event.getEventId());
                    eventQueue.offer(event);
                    return;
                }

                // 중복 제거 ID가 있는 경우 중복 확인
                if (event.getDeduplicationId() != null && !event.getDeduplicationId().isEmpty()) {
                    synchronized (processedDeduplicationIds) {
                        if (processedDeduplicationIds.containsKey(event.getDeduplicationId())) {
                            log.info("중복 이벤트 무시: {}, 중복 ID: {}", event.getEventId(), event.getDeduplicationId());
                            return;
                        }
                    }
                }

                try {
                    // 이벤트 처리 시작
                    log.info("이벤트 처리 시작: {}, 서버: {}", event.getEventId(), serverId);

                    // 이벤트 상태 업데이트
                    event.setStatus(DistributedEvent.EventStatus.PROCESSING);
                    event.setProcessingServerId(serverId);
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
                        executeCallback(event);
                    }

                    // 로컬 카운터 증가
                    processedEventCount++;

                    // 글로벌 통계 업데이트
                    IMap<String, Long> statsMap = hazelcastInstance.getMap(STATS_MAP_NAME);
                    statsMap.lock(PROCESSED_EVENTS_KEY);
                    try {
                        long currentCount = statsMap.get(PROCESSED_EVENTS_KEY);
                        statsMap.put(PROCESSED_EVENTS_KEY, currentCount + 1);
                    } finally {
                        statsMap.unlock(PROCESSED_EVENTS_KEY);
                    }

                    log.info("이벤트 처리 완료: {}, 서버: {}", event.getEventId(), serverId);

                    // 이벤트 버스로 처리 완료 알림
                    vertx.eventBus().publish("event.processed", event.toJson());
                } catch (Exception e) {
                    // 이벤트 처리 실패
                    log.error("이벤트 처리 실패: {}, 서버: {}", event.getEventId(), serverId, e);

                    event.setStatus(DistributedEvent.EventStatus.FAILED);
                    event.setRetryCount(event.getRetryCount() + 1);
                    eventMap.put(event.getEventId(), event);

                    // 로컬 카운터 증가
                    failedEventCount++;

                    // 글로벌 통계 업데이트
                    IMap<String, Long> statsMap = hazelcastInstance.getMap(STATS_MAP_NAME);
                    statsMap.lock(FAILED_EVENTS_KEY);
                    try {
                        long currentCount = statsMap.get(FAILED_EVENTS_KEY);
                        statsMap.put(FAILED_EVENTS_KEY, currentCount + 1);
                    } finally {
                        statsMap.unlock(FAILED_EVENTS_KEY);
                    }

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
        } catch (Exception e) {
            log.error("이벤트 처리 중 오류 발생", e);
        }
    }

    /**
     * 다음 콜백 재시도 처리
     * 콜백 재시도 큐에서 이벤트를 가져와 콜백을 재시도합니다.
     */
    private void processNextCallbackRetry() {
        try {
            IQueue<DistributedEvent> callbackRetryQueue = hazelcastInstance.getQueue(CALLBACK_RETRY_QUEUE);

            // 큐에서 이벤트 가져오기 (논블로킹)
            DistributedEvent event = callbackRetryQueue.poll();

            if (event != null) {
                // 지연 시간 확인 (마지막 시도 후 지정된 지연 시간이 지났는지)
                if (event.getLastCallbackAttemptAt() != null) {
                    LocalDateTime nextAttemptTime = event.getLastCallbackAttemptAt()
                            .plusNanos(event.getCallbackRetryDelayMs() * 1000000);

                    if (LocalDateTime.now().isBefore(nextAttemptTime)) {
                        // 아직 지연 시간이 지나지 않았으면 다시 큐에 넣음
                        callbackRetryQueue.offer(event);
                        return;
                    }
                }

                log.info("콜백 재시도: 이벤트={}, 시도={}/{}",
                        event.getEventId(), event.getCallbackRetryCount(), event.getCallbackMaxRetries());

                // 콜백 재시도
                executeCallback(event);
            }
        } catch (Exception e) {
            log.error("콜백 재시도 처리 중 오류 발생", e);
        }
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
    }

    /**
     * 콜백 실행
     * 이벤트 처리 완료 시 알림 URL로 알림을 보냅니다.
     *
     * @param event 처리된 이벤트
     */
    private void executeCallback(DistributedEvent event) {
        if (event.getNotificationUrl() == null || event.getNotificationUrl().isEmpty()) {
            return;
        }

        IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");

        // 마지막 콜백 시도 시간 업데이트
        event.setLastCallbackAttemptAt(LocalDateTime.now());

        log.info("콜백 실행: 이벤트={}, URL={}, 시도={}/{}",
                event.getEventId(), event.getNotificationUrl(),
                event.getCallbackRetryCount() + 1, event.getCallbackMaxRetries());

        // 헤더 설정
        io.vertx.core.MultiMap headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
        if (event.getCallbackHeaders() != null) {
            event.getCallbackHeaders().forEach(headers::add);
        }

        // 웹 클라이언트로 비동기 HTTP 요청
        webClient.requestAbs(HttpMethod.POST, event.getNotificationUrl())
            .putHeaders(headers)
            .sendJsonObject(event.toJson(), ar -> {
                if (ar.succeeded()) {
                    HttpResponse<Buffer> response = ar.result();
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        log.info("콜백 성공: 이벤트={}, 상태코드={}", event.getEventId(), response.statusCode());

                        // 이벤트가 CALLBACK_FAILED 상태였다면 COMPLETED로 변경
                        if (event.getStatus() == DistributedEvent.EventStatus.CALLBACK_FAILED) {
                            event.setStatus(DistributedEvent.EventStatus.COMPLETED);
                            eventMap.put(event.getEventId(), event);
                        }
                    } else {
                        log.error("콜백 실패: 이벤트={}, 상태코드={}", event.getEventId(), response.statusCode());
                        handleCallbackFailure(event);
                    }
                } else {
                    log.error("콜백 실패: 이벤트={}, 오류={}", event.getEventId(), ar.cause().getMessage());
                    handleCallbackFailure(event);
                }
            });
    }

    /**
     * 콜백 실패 처리
     * 콜백 실패 시 이벤트 상태를 업데이트하고 필요한 경우 재시도 큐에 추가합니다.
     *
     * @param event 콜백 실패한 이벤트
     */
    private void handleCallbackFailure(DistributedEvent event) {
        IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");
        IQueue<DistributedEvent> callbackRetryQueue = hazelcastInstance.getQueue(CALLBACK_RETRY_QUEUE);

        // 콜백 재시도 횟수 증가
        event.setCallbackRetryCount(event.getCallbackRetryCount() + 1);

        // 이벤트 상태를 CALLBACK_FAILED로 변경
        event.setStatus(DistributedEvent.EventStatus.CALLBACK_FAILED);

        // 이벤트 맵 업데이트
        eventMap.put(event.getEventId(), event);

        // 로컬 카운터 증가
        callbackFailedCount++;

        // 글로벌 통계 업데이트
        IMap<String, Long> statsMap = hazelcastInstance.getMap(STATS_MAP_NAME);
        statsMap.lock(CALLBACK_FAILED_EVENTS_KEY);
        try {
            long currentCount = statsMap.get(CALLBACK_FAILED_EVENTS_KEY);
            statsMap.put(CALLBACK_FAILED_EVENTS_KEY, currentCount + 1);
        } finally {
            statsMap.unlock(CALLBACK_FAILED_EVENTS_KEY);
        }

        // 최대 재시도 횟수보다 적게 시도했으면 재시도 큐에 추가
        if (event.getCallbackRetryCount() < event.getCallbackMaxRetries()) {
            try {
                // 재시도 큐에 추가
                callbackRetryQueue.offer(event);
                log.info("콜백 재시도 큐에 추가: 이벤트={}, 재시도={}/{}",
                        event.getEventId(), event.getCallbackRetryCount(), event.getCallbackMaxRetries());
            } catch (Exception e) {
                log.error("콜백 재시도 큐 추가 실패: 이벤트={}, 오류={}", event.getEventId(), e.getMessage());
            }
        } else {
            log.warn("콜백 최대 재시도 횟수 초과: 이벤트={}, 재시도={}/{}",
                    event.getEventId(), event.getCallbackRetryCount(), event.getCallbackMaxRetries());
        }
    }

    /**
     * 통계 정보 조회 처리
     *
     * @param message 통계 정보 조회 요청 메시지
     */
    private void handleGetStatistics(Message<Void> message) {
        // 글로벌 통계 맵에서 값 가져오기
        IMap<String, Long> statsMap = hazelcastInstance.getMap(STATS_MAP_NAME);
        long processedEvents = statsMap.getOrDefault(PROCESSED_EVENTS_KEY, 0L);
        long failedEvents = statsMap.getOrDefault(FAILED_EVENTS_KEY, 0L);
        long callbackFailedEvents = statsMap.getOrDefault(CALLBACK_FAILED_EVENTS_KEY, 0L);

        JsonObject statistics = new JsonObject()
                .put("serverId", serverId)
                .put("processedEvents", processedEvents)
                .put("failedEvents", failedEvents)
                .put("callbackFailedEvents", callbackFailedEvents);

        message.reply(statistics);
    }

    /**
     * 콜백 수동 재시도 처리
     *
     * @param message 콜백 재시도 요청 메시지
     */
    private void handleRetryCallback(Message<String> message) {
        String eventId = message.body();

        try {
            IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");
            IQueue<DistributedEvent> callbackRetryQueue = hazelcastInstance.getQueue(CALLBACK_RETRY_QUEUE);

            DistributedEvent event = eventMap.get(eventId);

            if (event == null) {
                log.warn("콜백 재시도 실패: 이벤트를 찾을 수 없음, ID={}", eventId);
                message.fail(404, "이벤트를 찾을 수 없음: " + eventId);
                return;
            }

            if (event.getStatus() != DistributedEvent.EventStatus.CALLBACK_FAILED) {
                log.warn("콜백 재시도 실패: 콜백 실패 상태가 아님, ID={}, 상태={}", eventId, event.getStatus());
                message.fail(400, "콜백 실패 상태가 아닌 이벤트: " + eventId);
                return;
            }

            // 콜백 재시도 횟수 초기화
            event.setCallbackRetryCount(0);

            // 이벤트 맵 업데이트
            eventMap.put(eventId, event);

            // 콜백 재시도 큐에 추가
            boolean offered = callbackRetryQueue.offer(event);

            if (offered) {
                log.info("콜백 수동 재시도 예약됨: 이벤트={}", eventId);
                message.reply(new JsonObject()
                        .put("success", true)
                        .put("message", "콜백 재시도가 예약되었습니다.")
                        .put("eventId", eventId));
            } else {
                log.error("콜백 수동 재시도 실패: 큐에 추가 실패, 이벤트={}", eventId);
                message.fail(500, "콜백 재시도 큐에 추가 실패: " + eventId);
            }
        } catch (Exception e) {
            log.error("콜백 재시도 처리 중 오류 발생", e);
            message.fail(500, "콜백 재시도 처리 중 오류 발생: " + e.getMessage());
        }
    }
}
