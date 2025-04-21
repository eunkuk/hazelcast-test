# 헤이즐캐스트를 활용한 다중 서버 환경에서의 이벤트 위임 및 큐 예제

이 프로젝트는 Hazelcast를 활용하여 다중 서버 환경에서 이벤트를 위임하고 큐를 통해 처리하는 예제를 구현한 것입니다. 분산 환경에서의 이벤트 처리와 성능 테스트를 위한 기능을 포함하고 있습니다.

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
- Spring Boot 3.4.4
- Hazelcast 5.3.6
- Spring Boot Actuator
- Micrometer Prometheus (모니터링)
- Bootstrap 5 (웹 인터페이스)

## 아키텍처

이 프로젝트는 다음과 같은 구성 요소로 이루어져 있습니다:

1. **Hazelcast 설정 (HazelcastConfig)**: 분산 데이터 구조 설정
2. **이벤트 모델 (DistributedEvent)**: 분산 환경에서 전달되는 이벤트 정의
3. **서버 식별 서비스 (ServerIdentificationService)**: 각 서버를 고유하게 식별
4. **이벤트 생성 서비스 (EventProducerService)**: 이벤트 생성 및 큐에 추가
5. **이벤트 소비 서비스 (EventConsumerService)**: 큐에서 이벤트를 가져와 처리
6. **REST API (EventController)**: 이벤트 생성 및 조회를 위한 API 제공
7. **웹 인터페이스**: 이벤트 시스템을 시각적으로 테스트하기 위한 UI

## 시작하기

### 사전 요구사항

- Java 17 이상
- Gradle
- 멀티캐스트를 지원하는 네트워크 환경 (또는 application.properties 설정 변경)

### 설치 및 실행 방법

1. 프로젝트 클론
   ```
   git clone https://github.com/yourusername/spring-mcp-test.git
   cd spring-mcp-test
   ```

2. 애플리케이션 빌드 및 실행
   ```
   ./gradlew bootRun
   ```

3. 웹 브라우저에서 접속
   ```
   http://localhost:8080
   ```

4. 다중 서버 환경 테스트를 위해 여러 인스턴스 실행 (다른 포트 사용)
   ```
   ./gradlew bootRun --args='--server.port=8081'
   ./gradlew bootRun --args='--server.port=8082'
   ```

## API 사용 방법

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

## SQS 스타일 기능 사용 가이드

이 프로젝트는 Amazon SQS와 유사한 메시징 기능을 제공합니다. 다음은 각 기능에 대한 설명과 사용 방법입니다.

### 알림 (Notifications)

이벤트 처리가 완료되면 지정된 URL로 HTTP 요청을 보내는 기능입니다.

**API 사용 예시:**
```bash
# 알림 URL과 헤더를 포함한 이벤트 생성
curl -X POST "http://localhost:8080/api/events/advanced?eventType=NOTIFICATION&payload=%7B%22message%22%3A%22Hello%22%7D&notificationUrl=https%3A%2F%2Fexample.com%2Fnotification&callbackHeaders%5B'Content-Type'%5D=application%2Fjson"
```

**웹 인터페이스 사용 방법:**
1. 웹 인터페이스에서 "고급 (SQS 스타일)" 탭 선택
2. "알림 URL" 필드에 이벤트 처리 완료 시 호출할 URL 입력 (예: https://example.com/notification)
3. "콜백 헤더" 필드에 JSON 형식으로 헤더 입력 (예: {"Authorization": "Bearer token"})

### 콜백 실패 처리 (Callback Failure Handling)

알림 URL 호출이 실패할 경우 별도로 관리하고 재시도할 수 있는 기능입니다.

**특징:**
- 알림 실패 시 이벤트 상태가 `CALLBACK_FAILED`로 변경됨
- 콜백 실패 이벤트는 별도의 재시도 큐에서 관리됨
- 콜백 재시도 횟수와 지연 시간을 별도로 설정 가능
- 웹 인터페이스에서 콜백 실패 이벤트 확인 및 수동 재시도 가능

**API 사용 예시:**
```bash
# 콜백 실패 이벤트 재시도
curl -X POST "http://localhost:8080/api/events/retry-callback/이벤트ID"
```

**웹 인터페이스 사용 방법:**
1. 이벤트 목록에서 `CALLBACK_FAILED` 상태의 이벤트 확인
2. "콜백 재시도" 버튼 클릭하여 수동 재시도

### 메시지 그룹 (Message Groups)

FIFO(First-In-First-Out) 순서로 처리해야 하는 관련 메시지들을 그룹화하는 기능입니다.

**API 사용 예시:**
```bash
# 메시지 그룹 ID를 포함한 이벤트 생성
curl -X POST "http://localhost:8080/api/events/advanced?eventType=DATA_PROCESSING&payload=%7B%22orderId%22%3A%22123%22%7D&messageGroupId=order-processing"
```

**웹 인터페이스 사용 방법:**
1. 웹 인터페이스에서 "고급 (SQS 스타일)" 탭 선택
2. "메시지 그룹 ID" 필드에 그룹 식별자 입력 (예: order-123)

### 중복 제거 (Deduplication)

동일한 메시지가 중복으로 처리되는 것을 방지하는 기능입니다.

**API 사용 예시:**
```bash
# 중복 제거 ID를 포함한 이벤트 생성
curl -X POST "http://localhost:8080/api/events/advanced?eventType=NOTIFICATION&payload=%7B%22message%22%3A%22Hello%22%7D&deduplicationId=msg-20230601-001"
```

**웹 인터페이스 사용 방법:**
1. 웹 인터페이스에서 "고급 (SQS 스타일)" 탭 선택
2. "중복 제거 ID" 필드에 고유 식별자 입력 (예: msg-20230601-001)

### 재시도 메커니즘 (Retry Mechanism)

이벤트 처리 실패 시 자동으로 재시도하는 기능입니다.

**API 사용 예시:**
```bash
# 최대 재시도 횟수와 지연 시간을 지정한 이벤트 생성
curl -X POST "http://localhost:8080/api/events/advanced?eventType=HEAVY_TASK&payload=%7B%22taskId%22%3A%22456%22%7D&maxRetries=5&retryDelayMs=10000"
```

**웹 인터페이스 사용 방법:**
1. 웹 인터페이스에서 "고급 (SQS 스타일)" 탭 선택
2. "최대 재시도 횟수" 필드에 숫자 입력 (예: 5)
3. "재시도 지연 (ms)" 필드에 밀리초 단위로 지연 시간 입력 (예: 10000)

### 가시성 타임아웃 (Visibility Timeout)

이벤트가 처리 중일 때 다른 소비자에게 보이지 않도록 하는 기능입니다.

**API 사용 예시:**
```bash
# 가시성 타임아웃을 지정한 이벤트 생성
curl -X POST "http://localhost:8080/api/events/advanced?eventType=DATA_PROCESSING&payload=%7B%22fileId%22%3A%22789%22%7D&visibilityTimeoutMs=60000"
```

**웹 인터페이스 사용 방법:**
1. 웹 인터페이스에서 "고급 (SQS 스타일)" 탭 선택
2. "가시성 타임아웃 (ms)" 필드에 밀리초 단위로 시간 입력 (예: 60000)

### 모든 기능을 조합한 예시

**API 사용 예시:**
```bash
# 모든 SQS 스타일 기능을 사용한 이벤트 생성
curl -X POST "http://localhost:8080/api/events/advanced?eventType=HEAVY_TASK&payload=%7B%22jobId%22%3A%22abc123%22%7D&notificationUrl=https%3A%2F%2Fexample.com%2Fnotification&messageGroupId=job-processing&deduplicationId=job-abc123&maxRetries=5&retryDelayMs=10000&visibilityTimeoutMs=60000"
```

**웹 인터페이스 사용 방법:**
1. 웹 인터페이스에서 "고급 (SQS 스타일)" 탭 선택
2. 모든 필드를 적절히 입력
3. "고급 이벤트 생성" 버튼 클릭

## 성능 테스트

웹 인터페이스의 성능 테스트 기능을 사용하여 다양한 조건에서 시스템의 성능을 측정할 수 있습니다:

1. 이벤트 수를 조절하여 시스템의 처리량 측정
2. 다양한 이벤트 유형을 선택하여 처리 시간에 따른 성능 변화 관찰
3. 여러 서버 인스턴스를 실행하여 분산 처리 효과 확인

## 프로젝트 구조

```
src/main/java/kr/com/hazelcasttest/
├── hazelcasttestApplication.java    # 애플리케이션 진입점
├── config/
│   └── HazelcastConfig.java         # Hazelcast 설정
├── controller/
│   └── EventController.java         # REST API 컨트롤러
├── model/
│   └── DistributedEvent.java        # 이벤트 모델
└── service/
    ├── ServerIdentificationService.java  # 서버 식별 서비스
    ├── EventProducerService.java         # 이벤트 생성 서비스
    ├── EventConsumerService.java         # 이벤트 소비 서비스
    └── CallbackService.java              # 콜백 처리 서비스
```

## 주의사항

- 멀티캐스트 설정이 네트워크에서 지원되지 않는 경우, `application.properties`에서 `hazelcast.network.join.multicast.enabled=false`로 설정하고 TCP-IP 조인 방식을 사용하세요.
- 실제 프로덕션 환경에서는 보안 설정을 추가하는 것이 좋습니다.
- 대량의 이벤트를 처리할 때는 서버 리소스를 모니터링하세요.
