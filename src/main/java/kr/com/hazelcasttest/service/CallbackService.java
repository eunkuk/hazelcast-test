package kr.com.hazelcasttest.service;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import kr.com.hazelcasttest.model.DistributedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 콜백 서비스
 * 이벤트 처리 완료 시 알림 URL로 알림을 보내는 서비스입니다.
 * 콜백 실패 시 재시도 기능을 제공합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallbackService {

    private final HazelcastInstance hazelcastInstance;
    private final RestTemplate restTemplate = new RestTemplate();
    private final Executor executor = Executors.newFixedThreadPool(10); // 콜백 처리를 위한 스레드 풀

    // 콜백 실패 이벤트를 위한 큐 이름
    private static final String CALLBACK_RETRY_QUEUE = "callbackRetryQueue";

    /**
     * 콜백 실행
     * 이벤트 처리 완료 시 알림 URL로 알림을 보냅니다.
     *
     * @param event 처리된 이벤트
     * @return 콜백 실행 결과 (비동기)
     */
    public CompletableFuture<Boolean> executeCallback(DistributedEvent event) {
        if (event.getNotificationUrl() == null || event.getNotificationUrl().isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        // 이벤트 맵 참조
        IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 마지막 콜백 시도 시간 업데이트
                event.setLastCallbackAttemptAt(LocalDateTime.now());

                log.info("콜백 실행: 이벤트={}, URL={}, 시도={}/{}",
                        event.getEventId(), event.getNotificationUrl(),
                        event.getCallbackRetryCount() + 1, event.getCallbackMaxRetries());

                HttpHeaders headers = new HttpHeaders();
                if (event.getCallbackHeaders() != null) {
                    event.getCallbackHeaders().forEach(headers::add);
                }

                HttpEntity<DistributedEvent> requestEntity = new HttpEntity<>(event, headers);
                restTemplate.postForEntity(event.getNotificationUrl(), requestEntity, String.class);

                log.info("콜백 성공: 이벤트={}", event.getEventId());

                // 이벤트가 CALLBACK_FAILED 상태였다면 COMPLETED로 변경
                if (event.getStatus() == DistributedEvent.EventStatus.CALLBACK_FAILED) {
                    event.setStatus(DistributedEvent.EventStatus.COMPLETED);
                    eventMap.put(event.getEventId(), event);
                }

                return true;
            } catch (Exception e) {
                log.error("콜백 실패: 이벤트={}, 오류={}", event.getEventId(), e.getMessage());

                // 콜백 실패 처리
                handleCallbackFailure(event);

                return false;
            }
        }, executor);
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
     * 콜백 실패 이벤트 수 조회
     *
     * @return 콜백 실패 이벤트 수
     */
    public int getCallbackFailedCount() {
        IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");
        return (int) eventMap.values().stream()
                .filter(event -> event.getStatus() == DistributedEvent.EventStatus.CALLBACK_FAILED)
                .count();
    }

    /**
     * 콜백 재시도 큐 크기 조회
     *
     * @return 콜백 재시도 큐 크기
     */
    public int getCallbackRetryQueueSize() {
        IQueue<DistributedEvent> callbackRetryQueue = hazelcastInstance.getQueue(CALLBACK_RETRY_QUEUE);
        return callbackRetryQueue.size();
    }

    /**
     * 콜백 수동 재시도
     * 콜백 실패 이벤트를 수동으로 재시도합니다.
     *
     * @param eventId 이벤트 ID
     * @return 재시도 성공 여부
     */
    public boolean retryCallback(String eventId) {
        IMap<String, DistributedEvent> eventMap = hazelcastInstance.getMap("eventMap");
        IQueue<DistributedEvent> callbackRetryQueue = hazelcastInstance.getQueue(CALLBACK_RETRY_QUEUE);

        DistributedEvent event = eventMap.get(eventId);

        if (event == null) {
            log.warn("콜백 재시도 실패: 이벤트를 찾을 수 없음, ID={}", eventId);
            return false;
        }

        if (event.getStatus() != DistributedEvent.EventStatus.CALLBACK_FAILED) {
            log.warn("콜백 재시도 실패: 콜백 실패 상태가 아님, ID={}, 상태={}", eventId, event.getStatus());
            return false;
        }

        // 콜백 재시도 횟수 초기화
        event.setCallbackRetryCount(0);

        // 이벤트 맵 업데이트
        eventMap.put(eventId, event);

        // 콜백 재시도 큐에 추가
        boolean offered = callbackRetryQueue.offer(event);

        if (offered) {
            log.info("콜백 수동 재시도 예약됨: 이벤트={}", eventId);
        } else {
            log.error("콜백 수동 재시도 실패: 큐에 추가 실패, 이벤트={}", eventId);
        }

        return offered;
    }
}
