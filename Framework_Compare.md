# Spring Boot vs Spring WebFlux vs Vert.x 비교
## SQS와 같은 분산 이벤트 처리 시스템 구현 시 장단점 분석

## 1. 개요

이 문서는 Amazon SQS와 같은 분산 이벤트 처리 시스템을 구현할 때 Spring Boot, Spring WebFlux, Vert.x 세 가지 프레임워크의 장단점을 비교 분석합니다. 각 프레임워크의 특성, 성능, 개발 복잡성 등을 고려하여 적합한 선택을 할 수 있도록 도움을 제공합니다.

## 2. 프레임워크 소개

### 2.1 Spring Boot (전통적인 블로킹 모델)

Spring Boot는 Spring 프레임워크를 기반으로 한 애플리케이션 개발을 간소화하는 도구입니다. 기본적으로 서블릿 기반의 블로킹 I/O 모델을 사용하며, 멀티스레드 방식으로 요청을 처리합니다.

### 2.2 Spring WebFlux (리액티브 모델)

Spring 5에서 도입된 WebFlux는 비동기 논블로킹 리액티브 프로그래밍 모델을 제공합니다. Reactor 라이브러리를 기반으로 하며, 이벤트 루프 모델을 사용하여 적은 수의 스레드로 많은 요청을 처리할 수 있습니다.

### 2.3 Vert.x (이벤트 기반 모델)

Vert.x는 JVM 위에서 동작하는 이벤트 기반 비동기 애플리케이션 프레임워크입니다. 경량화된 이벤트 루프 모델을 사용하며, 다양한 언어를 지원하고 폴리글랏 프로그래밍이 가능합니다.

## 3. 아키텍처 비교

### 3.1 Spring Boot 아키텍처

- **동시성 모델**: 멀티스레드 블로킹 I/O
- **요청 처리**: 각 요청마다 스레드 할당
- **구성 요소**:
  - 설정 클래스 (HazelcastConfig)
  - 서비스 클래스 (EventProducerService, EventConsumerService, CallbackService)
  - 컨트롤러 (EventController)
- **통신 방식**: 직접 메서드 호출
- **스케일링**: 스레드 풀 크기에 의존

### 3.2 Spring WebFlux 아키텍처

- **동시성 모델**: 이벤트 루프 기반 논블로킹 I/O
- **요청 처리**: 적은 수의 스레드로 많은 요청 처리
- **구성 요소**:
  - 설정 클래스 (HazelcastConfig)
  - 리액티브 서비스 (Reactive EventProducerService, EventConsumerService)
  - 함수형 엔드포인트 또는 리액티브 컨트롤러
- **통신 방식**: 리액티브 스트림 (Flux, Mono)
- **스케일링**: 이벤트 루프 모델로 효율적인 리소스 활용

### 3.3 Vert.x 아키텍처

- **동시성 모델**: 이벤트 루프 기반 논블로킹 I/O
- **요청 처리**: 이벤트 루프 스레드와 워커 스레드 분리
- **구성 요소**:
  - 메인 버티클 (MainVerticle)
  - 이벤트 생성 버티클 (EventProducerVerticle)
  - 이벤트 소비 워커 버티클 (EventConsumerVerticle)
  - API 버티클 (ApiVerticle)
- **통신 방식**: 이벤트 버스를 통한 메시지 전달
- **스케일링**: 버티클 인스턴스 복제를 통한 수평적 확장

## 4. 성능 비교

### 4.1 처리량 (Throughput)

| 프레임워크 | 처리량 | 상대적 성능 |
|----------|--------|------------|
| Spring Boot | 중간 | 기준 |
| Spring WebFlux | 높음 | 1.3-2배 향상 |
| Vert.x | 매우 높음 | 1.5-3배 향상 |

### 4.2 지연 시간 (Latency)

| 프레임워크 | 지연 시간 | 상대적 성능 |
|----------|----------|------------|
| Spring Boot | 중간 | 기준 |
| Spring WebFlux | 낮음 | 20-40% 감소 |
| Vert.x | 매우 낮음 | 30-50% 감소 |

### 4.3 리소스 사용량

| 프레임워크 | 메모리 사용량 | CPU 사용량 | 스레드 수 |
|----------|-------------|----------|----------|
| Spring Boot | 높음 | 중간 | 많음 (요청당 스레드) |
| Spring WebFlux | 중간 | 중간 | 적음 (CPU 코어 수 기반) |
| Vert.x | 낮음 | 낮음 | 매우 적음 (이벤트 루프 + 워커) |

## 5. 개발 복잡성

### 5.1 학습 곡선

| 프레임워크 | 학습 곡선 | 이유 |
|----------|----------|-----|
| Spring Boot | 낮음 | 익숙한 동기식 프로그래밍 모델, 풍부한 문서와 커뮤니티 |
| Spring WebFlux | 높음 | 리액티브 프로그래밍 패러다임 학습 필요, 디버깅 어려움 |
| Vert.x | 중간-높음 | 이벤트 기반 프로그래밍 모델, 비동기 코드 작성 필요 |

### 5.2 코드 복잡성

#### Spring Boot 예시 (이벤트 생성)
```
// Spring Boot 예시
@Service
public class EventProducerService {
    public DistributedEvent produceEvent(String eventType, String payload) {
        DistributedEvent event = DistributedEvent.builder()
                .eventType(eventType)
                .payload(payload)
                .build();

        eventQueue.offer(event, 5, TimeUnit.SECONDS);
        return event;
    }
}
```

#### Spring WebFlux 예시 (이벤트 생성)
```
// Spring WebFlux 예시
@Service
public class EventProducerService {
    public Mono<DistributedEvent> produceEvent(String eventType, String payload) {
        return Mono.fromCallable(() -> {
            DistributedEvent event = DistributedEvent.builder()
                    .eventType(eventType)
                    .payload(payload)
                    .build();

            return event;
        }).flatMap(event -> 
            Mono.fromCompletionStage(eventQueue.offerAsync(event, 5, TimeUnit.SECONDS))
                .thenReturn(event)
        );
    }
}
```

#### Vert.x 예시 (이벤트 생성)
```
// Vert.x 예시
public class EventProducerVerticle extends AbstractVerticle {
    public Future<DistributedEvent> produceEvent(String eventType, String payload) {
        Promise<DistributedEvent> promise = Promise.promise();

        DistributedEvent event = new DistributedEvent(eventType, payload);

        vertx.executeBlocking(
            blockingPromise -> {
                eventQueue.offer(event, 5, TimeUnit.SECONDS);
                blockingPromise.complete(event);
            },
            result -> {
                if (result.succeeded()) {
                    promise.complete((DistributedEvent) result.result());
                } else {
                    promise.fail(result.cause());
                }
            }
        );

        return promise.future();
    }
}
```

### 5.3 테스트 용이성

| 프레임워크 | 테스트 용이성 | 이유 |
|----------|-------------|-----|
| Spring Boot | 높음 | 단순한 동기식 코드, 풍부한 테스트 도구 |
| Spring WebFlux | 중간 | StepVerifier 등 특수 도구 필요, 비동기 테스트 복잡성 |
| Vert.x | 중간 | VertxUnit 등 특수 도구 필요, 비동기 테스트 복잡성 |

## 6. SQS 유사 시스템 구현 시 고려사항

### 6.1 메시지 큐 구현

#### Spring Boot
- Hazelcast IQueue를 직접 사용
- 블로킹 API로 간단한 구현
- 스레드 풀을 통한 병렬 처리
- 동기식 코드로 이해하기 쉬움

#### Spring WebFlux
- Hazelcast IQueue를 리액티브 래퍼로 감싸서 사용
- 논블로킹 API로 구현
- 리액티브 스트림을 통한 백프레셔 지원
- 복잡한 비동기 코드 흐름

#### Vert.x
- Hazelcast IQueue를 Vert.x 이벤트 루프와 통합
- 이벤트 버스를 통한 메시지 전달
- 워커 버티클을 통한 블로킹 작업 처리
- 이벤트 기반 프로그래밍 모델

### 6.2 메시지 가시성 타임아웃

#### Spring Boot
```
// Spring Boot 예시
if (event.getVisibleAfter() != null && Instant.now().isBefore(event.getVisibleAfter())) {
    // 아직 가시성 타임아웃이 지나지 않았으면 다시 큐에 넣음
    eventQueue.offer(event);
    continue;
}
```

#### Spring WebFlux
```
// Spring WebFlux 예시
return Mono.defer(() -> {
    if (event.getVisibleAfter() != null && Instant.now().isBefore(event.getVisibleAfter())) {
        // 아직 가시성 타임아웃이 지나지 않았으면 다시 큐에 넣음
        return Mono.fromCompletionStage(eventQueue.offerAsync(event))
                  .then(Mono.empty());
    }
    return Mono.just(event);
});
```

#### Vert.x
```
// Vert.x 예시
if (event.getVisibleAfter() != null && Instant.now().isBefore(event.getVisibleAfter())) {
    // 아직 가시성 타임아웃이 지나지 않았으면 다시 큐에 넣음
    vertx.executeBlocking(promise -> {
        eventQueue.offer(event);
        promise.complete();
    }, false, ar -> {});
    return;
}
```

### 6.3 재시도 메커니즘

#### Spring Boot
- 스케줄링된 스레드 풀 사용
- 직접적인 재시도 로직 구현
- 블로킹 방식의 지연 처리

#### Spring WebFlux
- 리액티브 스케줄러 사용
- Mono.delayElement()를 통한 비동기 지연
- 리액티브 스트림을 통한 재시도 체인

#### Vert.x
- vertx.setTimer() 사용
- 이벤트 루프에 영향 없는 비동기 지연
- 이벤트 기반 재시도 메커니즘

### 6.4 분산 처리 및 클러스터링

#### Spring Boot
- Hazelcast 클러스터 직접 설정
- 분산 맵과 큐를 통한 상태 공유
- 스레드 풀을 통한 로컬 병렬 처리

#### Spring WebFlux
- Hazelcast 클러스터 직접 설정
- 리액티브 래퍼를 통한 분산 데이터 접근
- 이벤트 루프를 통한 효율적인 I/O 처리

#### Vert.x
- Vert.x 클러스터 매니저로 Hazelcast 사용
- 클러스터링된 이벤트 버스를 통한 통신
- 버티클 배포를 통한 분산 처리

## 7. 각 프레임워크별 장단점

### 7.1 Spring Boot

#### 장점
- 익숙한 동기식 프로그래밍 모델로 개발 용이
- 풍부한 생태계와 라이브러리 지원
- 쉬운 디버깅 및 트러블슈팅
- 광범위한 커뮤니티 지원
- 다양한 통합 기능 (Spring Data, Security 등)

#### 단점
- 높은 메모리 사용량 (스레드 당 메모리 오버헤드)
- 제한된 동시성 (스레드 풀 크기에 의존)
- C10K 문제에 취약 (많은 동시 연결 처리 어려움)
- 블로킹 I/O로 인한 리소스 낭비 가능성

### 7.2 Spring WebFlux

#### 장점
- 논블로킹 I/O로 높은 동시성 처리
- 적은 수의 스레드로 효율적인 리소스 사용
- 백프레셔를 통한 부하 제어
- Spring Boot와 동일한 생태계 활용 가능
- 함수형 프로그래밍 스타일 지원

#### 단점
- 가파른 학습 곡선 (리액티브 프로그래밍 패러다임)
- 복잡한 디버깅 (스택 트레이스 추적 어려움)
- 모든 라이브러리가 리액티브하지 않음 (블로킹 코드 주의 필요)
- 코드 복잡성 증가

### 7.3 Vert.x

#### 장점
- 매우 높은 성능과 낮은 지연 시간
- 경량화된 이벤트 루프 모델
- 다양한 언어 지원 (폴리글랏)
- 이벤트 버스를 통한 효율적인 통신
- 마이크로서비스 아키텍처에 적합

#### 단점
- Spring 생태계와 다른 프로그래밍 모델
- 이벤트 기반 프로그래밍에 익숙해져야 함
- 상대적으로 작은 커뮤니티
- 블로킹 코드 관리에 주의 필요

## 8. 사용 사례별 권장 프레임워크

### 8.1 대규모 분산 메시징 시스템 (SQS 대체)
- **권장**: Vert.x
- **이유**: 최고의 성능, 낮은 지연 시간, 효율적인 리소스 사용

### 8.2 중간 규모 이벤트 처리 시스템
- **권장**: Spring WebFlux
- **이유**: 좋은 성능과 Spring 생태계 활용 가능

### 8.3 간단한 메시징 시스템 또는 빠른 개발이 필요한 경우
- **권장**: Spring Boot
- **이유**: 쉬운 개발, 풍부한 생태계, 충분한 성능

### 8.4 마이크로서비스 간 이벤트 기반 통신
- **권장**: Vert.x 또는 Spring WebFlux
- **이유**: 효율적인 비동기 통신, 높은 동시성

## 9. 결론

SQS와 같은 분산 이벤트 처리 시스템을 구현할 때, 세 가지 프레임워크는 각각 다른 강점과 약점을 가지고 있습니다:

- **Spring Boot**: 개발 용이성과 풍부한 생태계를 중시하는 경우 적합
- **Spring WebFlux**: Spring 생태계를 유지하면서 높은 동시성이 필요한 경우 적합
- **Vert.x**: 최고의 성능과 리소스 효율성이 필요한 경우 적합

최종 선택은 프로젝트의 요구사항, 팀의 경험, 성능 목표, 그리고 개발 일정에 따라 달라질 수 있습니다. 대규모 분산 이벤트 처리가 핵심인 시스템에서는 Vert.x가 가장 적합할 수 있지만, Spring 생태계에 익숙한 팀이라면 Spring WebFlux가 좋은 절충안이 될 수 있습니다.
