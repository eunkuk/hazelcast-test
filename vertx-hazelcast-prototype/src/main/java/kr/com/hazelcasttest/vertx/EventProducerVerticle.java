package kr.com.hazelcasttest.vertx;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.topic.ITopic;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import kr.com.hazelcasttest.vertx.model.DistributedEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 이벤트 생산자 버티클
 * 이벤트를 생성하고 Hazelcast 큐에 추가하는 버티클입니다.
 */
@Slf4j
public class EventProducerVerticle extends AbstractVerticle {

    private HazelcastInstance hazelcastInstance;
    private String serverId;

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
        // 서버 ID 생성
        serverId = UUID.randomUUID().toString();
        log.info("이벤트 생산자 버티클 시작: 서버 ID = {}", serverId);

        // 이벤트 버스 핸들러 등록
        vertx.eventBus().consumer("event.create", this::handleCreateEvent);
        vertx.eventBus().consumer("event.create.advanced", this::handleCreateAdvancedEvent);
        vertx.eventBus().consumer("event.status", this::handleGetEventStatus);
        vertx.eventBus().consumer("event.queue.info", this::handleGetQueueInfo);

        startPromise.complete();
    }

    /**
     * 기본 이벤트 생성 처리
     *
     * @param message 이벤트 생성 요청 메시지
     */
    private void handleCreateEvent(Message<JsonObject> message) {
        JsonObject requestData = message.body();
        String eventType = requestData.getString("eventType");
        String payload = requestData.getString("payload");

        try {
            DistributedEvent event = produceEvent(eventType, payload);
            message.reply(event.toJson());
        } catch (Exception e) {
            log.error("이벤트 생성 실패", e);
            message.fail(500, "이벤트 생성 실패: " + e.getMessage());
        }
    }

    /**
     * 고급 이벤트 생성 처리
     *
     * @param message 고급 이벤트 생성 요청 메시지
     */
    private void handleCreateAdvancedEvent(Message<JsonObject> message) {
        JsonObject requestData = message.body();
        String eventType = requestData.getString("eventType");
        String payload = requestData.getString("payload");
        String notificationUrl = requestData.getString("notificationUrl");
        JsonObject callbackHeadersJson = requestData.getJsonObject("callbackHeaders", new JsonObject());
        String messageGroupId = requestData.getString("messageGroupId");
        String deduplicationId = requestData.getString("deduplicationId");
        int maxRetries = requestData.getInteger("maxRetries", 3);
        long retryDelayMs = requestData.getLong("retryDelayMs", 5000L);
        long visibilityTimeoutMs = requestData.getLong("visibilityTimeoutMs", 30000L);

        // JsonObject를 Map으로 변환
        Map<String, String> callbackHeaders = new HashMap<>();
        for (String key : callbackHeadersJson.fieldNames()) {
            callbackHeaders.put(key, callbackHeadersJson.getString(key));
        }

        try {
            DistributedEvent event = produceEvent(
                    eventType, payload, notificationUrl, callbackHeaders,
                    messageGroupId, deduplicationId, maxRetries,
                    retryDelayMs, visibilityTimeoutMs);
            message.reply(event.toJson());
        } catch (Exception e) {
            log.error("고급 이벤트 생성 실패", e);
            message.fail(500, "고급 이벤트 생성 실패: " + e.getMessage());
        }
    }

    /**
     * 이벤트 상태 조회 처리
     *
     * @param message 이벤트 상태 조회 요청 메시지
     */
    private void handleGetEventStatus(Message<String> message) {
        String eventId = message.body();

        try {
            IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");
            DistributedEvent event = eventMap.get(eventId);

            if (event != null) {
                message.reply(event.toJson());
            } else {
                message.fail(404, "이벤트를 찾을 수 없음: " + eventId);
            }
        } catch (Exception e) {
            log.error("이벤트 상태 조회 실패", e);
            message.fail(500, "이벤트 상태 조회 실패: " + e.getMessage());
        }
    }

    /**
     * 큐 정보 조회 처리
     *
     * @param message 큐 정보 조회 요청 메시지
     */
    private void handleGetQueueInfo(Message<Void> message) {
        try {
            IQueue<DistributedEvent> eventQueue = hazelcastInstance.getQueue("eventQueue");
            IQueue<DistributedEvent> callbackRetryQueue = hazelcastInstance.getQueue("callbackRetryQueue");

            JsonObject info = new JsonObject()
                    .put("queueSize", eventQueue.size())
                    .put("callbackRetryQueueSize", callbackRetryQueue.size())
                    .put("serverId", serverId);

            message.reply(info);
        } catch (Exception e) {
            log.error("큐 정보 조회 실패", e);
            message.fail(500, "큐 정보 조회 실패: " + e.getMessage());
        }
    }

    /**
     * 이벤트 생성 (기본 버전)
     *
     * @param eventType 이벤트 유형
     * @param payload 이벤트 데이터
     * @return 생성된 이벤트
     */
    private DistributedEvent produceEvent(String eventType, String payload) {
        return produceEvent(eventType, payload, null, null, null, null, 3, 5000, 30000);
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
    private DistributedEvent produceEvent(
            String eventType,
            String payload,
            String notificationUrl,
            Map<String, String> callbackHeaders,
            String messageGroupId,
            String deduplicationId,
            int maxRetries,
            long retryDelayMs,
            long visibilityTimeoutMs) {

        // 이벤트 생성
        DistributedEvent.DistributedEventBuilder builder = DistributedEvent.builder()
                .eventType(eventType)
                .payload(payload)
                .sourceServerId(serverId)
                .maxRetries(maxRetries)
                .retryDelayMs(retryDelayMs)
                .visibilityTimeoutMs(visibilityTimeoutMs);

        if (notificationUrl != null && !notificationUrl.isEmpty()) {
            builder.notificationUrl(notificationUrl);
        }

        if (callbackHeaders != null && !callbackHeaders.isEmpty()) {
            builder.callbackHeaders(callbackHeaders);
        }

        if (messageGroupId != null && !messageGroupId.isEmpty()) {
            builder.messageGroupId(messageGroupId);
        }

        if (deduplicationId != null && !deduplicationId.isEmpty()) {
            builder.deduplicationId(deduplicationId);
        }

        DistributedEvent event = builder.build();

        log.info("이벤트 생성: {}, 서버: {}", event.getEventId(), serverId);

        // 이벤트 상태 맵에 저장
        IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");
        eventMap.put(event.getEventId(), event);

        // 이벤트 큐에 추가
        IQueue<DistributedEvent> eventQueue = hazelcastInstance.getQueue("eventQueue");
        try {
            boolean offered = eventQueue.offer(event, 5, TimeUnit.SECONDS);
            if (offered) {
                // 이벤트 상태 업데이트
                event.setStatus(DistributedEvent.EventStatus.QUEUED);
                eventMap.put(event.getEventId(), event);
                log.info("이벤트가 큐에 추가됨: {}", event.getEventId());

                // 이벤트 토픽에 발행 (실시간 알림용)
                ITopic<DistributedEvent> eventTopic = hazelcastInstance.getTopic("eventTopic");
                eventTopic.publish(event);

                // 이벤트 버스로도 알림
                vertx.eventBus().publish("event.created", event.toJson());
            } else {
                log.warn("이벤트를 큐에 추가하지 못함: {}", event.getEventId());
                event.setStatus(DistributedEvent.EventStatus.FAILED);
                eventMap.put(event.getEventId(), event);
            }
        } catch (InterruptedException e) {
            log.error("이벤트 큐 추가 중 인터럽트 발생: {}", event.getEventId(), e);
            Thread.currentThread().interrupt();
            event.setStatus(DistributedEvent.EventStatus.FAILED);
            eventMap.put(event.getEventId(), event);
        }

        return event;
    }
}
