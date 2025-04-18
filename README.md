# 헤이즐캐스트를 활용한 다중 서버 환경에서의 이벤트 위임 및 큐 예제

이 프로젝트는 Hazelcast를 활용하여 다중 서버 환경에서 이벤트를 위임하고 큐를 통해 처리하는 예제를 구현한 것입니다. 분산 환경에서의 이벤트 처리와 성능 테스트를 위한 기능을 포함하고 있습니다.

## 기능

- Hazelcast 분산 큐를 활용한 이벤트 처리
- 다중 서버 간 이벤트 위임 및 공유
- 분산 맵을 통한 이벤트 상태 관리
- 분산 토픽을 통한 실시간 이벤트 알림
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
- **Response**: 큐 크기, 처리된 이벤트 수, 실패한 이벤트 수 등

### 성능 테스트

- **URL**: `/api/events/performance-test`
- **Method**: POST
- **Parameters**:
  - `count`: 생성할 이벤트 수 (기본값: 1000)
  - `eventType`: 이벤트 유형
- **Response**: 테스트 결과 (소요 시간, 초당 이벤트 수 등)

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
    └── EventConsumerService.java         # 이벤트 소비 서비스
```

## 주의사항

- 멀티캐스트 설정이 네트워크에서 지원되지 않는 경우, `application.properties`에서 `hazelcast.network.join.multicast.enabled=false`로 설정하고 TCP-IP 조인 방식을 사용하세요.
- 실제 프로덕션 환경에서는 보안 설정을 추가하는 것이 좋습니다.
- 대량의 이벤트를 처리할 때는 서버 리소스를 모니터링하세요.
