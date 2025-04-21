# Vert.x + Hazelcast 분산 이벤트 시스템

이 프로젝트는 Vert.x와 Hazelcast를 활용하여 다중 서버 환경에서 이벤트를 위임하고 큐를 통해 처리하는 예제를 구현한 것입니다. Spring Boot 버전에서 Vert.x로 마이그레이션되었으며, 동일한 기능을 제공하면서 더 나은 성능과 리소스 효율성을 제공합니다.

## 기능

- Hazelcast 분산 큐를 활용한 이벤트 처리
- 다중 서버 간 이벤트 위임 및 공유
- 분산 맵을 통한 이벤트 상태 관리
- 분산 토픽을 통한 실시간 이벤트 알림
- Amazon SQS와 유사한 고급 메시징 기능:
  - 알림 (이벤트 처리 완료 시 외부 시스템에 알림)
  - 메시지 그룹 (FIFO 순서 보장)
  - 중복 제거 (동일 메시지 중복 처리 방지)
  - 재시도 메커니즘 (실패 시 자동 재시도)
  - 가시성 타임아웃 (처리 중 메시지 숨김)
  - 콜백 실패 처리 (알림 실패 시 별도 관리 및 재시도)
- 성능 테스트 기능 제공
- 웹 인터페이스를 통한 시각화

## 기술 스택

- Java 17
- Vert.x 4.4.6
- Hazelcast 5.3.6
- Vert.x Web
- Vert.x Web Client
- Bootstrap 5 (웹 인터페이스)

## Spring Boot vs Vert.x 비교

| 특성 | Spring Boot | Vert.x |
|------|-------------|--------|
| 아키텍처 | 멀티스레드 블로킹 I/O | 이벤트 루프 기반 논블로킹 I/O |
| 동시성 모델 | 스레드 풀 기반 | 이벤트 루프 + 워커 버티클 |
| 통신 방식 | 직접 메서드 호출 | 이벤트 버스 기반 메시지 전달 |
| 확장성 | 수직적 확장에 적합 | 수평적 확장에 더 적합 |
| 리소스 사용 | 상대적으로 높은 메모리 사용 | 적은 메모리 사용, 효율적인 리소스 활용 |
| 시작 시간 | 상대적으로 느림 | 매우 빠름 |
| 개발 복잡성 | 상대적으로 낮음 (익숙한 모델) | 비동기 프로그래밍 패러다임 학습 필요 |

## 아키텍처

이 프로젝트는 다음과 같은 구성 요소로 이루어져 있습니다:

1. **MainVerticle**: 애플리케이션의 진입점으로, Hazelcast 클러스터 설정 및 다른 버티클 배포를 담당합니다.
2. **ApiVerticle**: REST API 및 정적 리소스를 제공하는 버티클입니다.
3. **EventProducerVerticle**: 이벤트를 생성하고 Hazelcast 큐에 추가하는 버티클입니다.
4. **EventConsumerVerticle**: Hazelcast 큐에서 이벤트를 가져와 처리하는 워커 버티클입니다.
5. **DistributedEvent**: 분산 환경에서 전달되는 이벤트를 나타내는 모델 클래스입니다.

## 시작하기

### 사전 요구사항

- Java 17 이상
- Gradle
- 멀티캐스트를 지원하는 네트워크 환경 (또는 Hazelcast 설정 변경)

### 설치 및 실행 방법

1. 프로젝트 클론
   ```
   git clone https://github.com/yourusername/vertx-hazelcast-prototype.git
   cd vertx-hazelcast-prototype
   ```

2. 애플리케이션 빌드 및 실행
   ```
   ./gradlew run
   ```

3. 웹 브라우저에서 접속
   ```
   http://localhost:8080
   ```

4. 다중 서버 환경 테스트를 위해 여러 인스턴스 실행 (다른 포트 사용)
   ```
   java -jar build/libs/vertx-hazelcast-prototype-0.0.1-SNAPSHOT-fat.jar -conf '{"http.port":8081}'
   java -jar build/libs/vertx-hazelcast-prototype-0.0.1-SNAPSHOT-fat.jar -conf '{"http.port":8082}'
   ```

## API 사용 방법

Spring Boot 버전과 동일한 API를 제공합니다:

### 서버 정보 조회

- **URL**: `/api/events/server-info`
- **Method**: GET
- **Response**: 서버 ID, 호스트명 등 서버 정보

### 이벤트 생성

- **URL**: `/api/events`
- **Method**: POST
- **Parameters**:
  - `eventType`: 이벤트 유형 (NOTIFICATION, DATA_PROCESSING, HEAVY_TASK)
  - `payload`: 이벤트 데이터 (JSON 문자열)
- **Response**: 생성된 이벤트 정보

### 이벤트 상태 조회

- **URL**: `/api/events/{eventId}`
- **Method**: GET
- **Response**: 이벤트 상태 정보

### 큐 상태 조회

- **URL**: `/api/events/queue-info`
- **Method**: GET
- **Response**: 큐 크기, 처리된 이벤트 수, 처리 실패 이벤트 수, 콜백 실패 이벤트 수, 콜백 재시도 큐 크기 등

### 콜백 실패 이벤트 재시도

- **URL**: `/api/events/retry-callback/{eventId}`
- **Method**: POST
- **Response**: 재시도 결과 (성공 여부, 메시지)

### 고급 이벤트 생성 (SQS 스타일)

- **URL**: `/api/events/advanced`
- **Method**: POST
- **Parameters**:
  - `eventType`: 이벤트 유형 (NOTIFICATION, DATA_PROCESSING, HEAVY_TASK)
  - `payload`: 이벤트 데이터 (JSON 문자열)
  - `notificationUrl` (선택): 이벤트 처리 완료 시 호출할 URL
  - `callbackHeaders` (선택): 콜백 요청에 포함할 헤더
  - `messageGroupId` (선택): FIFO 처리를 위한 그룹 식별자
  - `deduplicationId` (선택): 중복 방지를 위한 식별자
  - `maxRetries` (선택): 최대 재시도 횟수 (기본값: 3)
  - `retryDelayMs` (선택): 재시도 지연 시간 (밀리초, 기본값: 5000)
  - `visibilityTimeoutMs` (선택): 가시성 타임아웃 (밀리초, 기본값: 30000)
- **Response**: 생성된 이벤트 정보

### 성능 테스트

- **URL**: `/api/events/performance-test`
- **Method**: POST
- **Parameters**:
  - `count`: 생성할 이벤트 수 (기본값: 1000)
  - `eventType`: 이벤트 유형
- **Response**: 테스트 결과 (소요 시간, 초당 이벤트 수 등)

## Vert.x 구현의 주요 특징

1. **이벤트 루프 모델**: Vert.x는 이벤트 루프 기반의 논블로킹 I/O 모델을 사용하여 적은 스레드로 높은 동시성을 처리합니다.

2. **버티클 기반 아키텍처**: 애플리케이션이 여러 독립적인 버티클로 구성되어 있어 모듈성과 확장성이 향상됩니다.

3. **이벤트 버스 통신**: 버티클 간 통신은 이벤트 버스를 통해 이루어지며, 이는 분산 환경에서 효율적인 메시지 전달을 가능하게 합니다.

4. **워커 버티클**: 블로킹 작업은 워커 버티클에서 처리되어 이벤트 루프가 차단되지 않도록 합니다.

5. **비동기 API**: 모든 API는 비동기적으로 설계되어 있어 높은 처리량과 낮은 지연 시간을 제공합니다.

## 성능 비교

Vert.x 구현은 Spring Boot 구현에 비해 다음과 같은 성능 이점을 제공합니다:

1. **높은 처리량**: 논블로킹 I/O와 이벤트 루프 모델로 인해 더 많은 이벤트를 동시에 처리할 수 있습니다.

2. **낮은 지연 시간**: 스레드 전환 오버헤드가 적어 응답 시간이 더 빠릅니다.

3. **적은 리소스 사용**: 적은 수의 스레드로 높은 동시성을 처리하여 메모리 사용량이 적습니다.

4. **빠른 시작 시간**: Spring Boot에 비해 애플리케이션 시작 시간이 매우 빠릅니다.

## 주의사항

- 멀티캐스트 설정이 네트워크에서 지원되지 않는 경우, `MainVerticle.java`에서 Hazelcast 설정을 수정하여 TCP-IP 조인 방식을 사용하세요.
- 실제 프로덕션 환경에서는 보안 설정을 추가하는 것이 좋습니다.
- 대량의 이벤트를 처리할 때는 서버 리소스를 모니터링하세요.
