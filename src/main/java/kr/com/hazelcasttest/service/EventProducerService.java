package kr.com.hazelcasttest.service;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.topic.ITopic;
import kr.com.hazelcasttest.model.DistributedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 이벤트 생성 서비스
 * 분산 이벤트를 생성하고 헤이즐캐스트 큐에 추가하는 서비스입니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventProducerService {

    private final HazelcastInstance hazelcastInstance;
    private final ServerIdentificationService serverIdentificationService;

    /**
     * 이벤트 생성 및 큐에 추가
     *
     * @param eventType 이벤트 유형
     * @param payload   이벤트 데이터 (JSON 문자열)
     * @return 생성된 이벤트
     */
    public DistributedEvent produceEvent(String eventType, String payload) {
        // 이벤트 생성
        DistributedEvent event = DistributedEvent.builder()
                .eventType(eventType)
                .payload(payload)
                .sourceServerId(serverIdentificationService.getServerId())
                .build();

        log.info("이벤트 생성: {}, 서버: {}", event.getEventId(), serverIdentificationService.getServerId());

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

    /**
     * 이벤트 상태 조회
     *
     * @param eventId 이벤트 ID
     * @return 이벤트 객체 (없으면 null)
     */
    public DistributedEvent getEventStatus(String eventId) {
        IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");
        return eventMap.get(eventId);
    }

    /**
     * 큐 크기 조회
     *
     * @return 큐에 있는 이벤트 수
     */
    public int getQueueSize() {
        IQueue<DistributedEvent> eventQueue = hazelcastInstance.getQueue("eventQueue");
        return eventQueue.size();
    }
}
