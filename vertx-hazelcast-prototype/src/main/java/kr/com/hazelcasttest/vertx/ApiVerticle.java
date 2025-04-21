package kr.com.hazelcasttest.vertx;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 버티클
 * REST API 및 정적 리소스를 제공하는 버티클입니다.
 */
@Slf4j
public class ApiVerticle extends AbstractVerticle {

    private String serverId;
    private String serverHost;

    @Override
    public void start(Promise<Void> startPromise) {
        // 서버 ID 및 호스트 설정
        serverId = UUID.randomUUID().toString();
        serverHost = System.getenv("HOSTNAME");
        if (serverHost == null) {
            serverHost = "localhost";
        }

        log.info("API 버티클 시작: 서버 ID = {}, 호스트 = {}", serverId, serverHost);

        // HTTP 서버 및 라우터 생성
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);

        // CORS 설정
        Set<String> allowedHeaders = new HashSet<>();
        allowedHeaders.add("x-requested-with");
        allowedHeaders.add("Access-Control-Allow-Origin");
        allowedHeaders.add("origin");
        allowedHeaders.add("Content-Type");
        allowedHeaders.add("accept");

        Set<HttpMethod> allowedMethods = new HashSet<>();
        allowedMethods.add(HttpMethod.GET);
        allowedMethods.add(HttpMethod.POST);
        allowedMethods.add(HttpMethod.OPTIONS);

        router.route().handler(CorsHandler.create("*")
                .allowedHeaders(allowedHeaders)
                .allowedMethods(allowedMethods));

        // 요청 본문 처리
        router.route().handler(BodyHandler.create());

        // API 엔드포인트 설정
        router.get("/api/events/server-info").handler(this::handleGetServerInfo);
        router.post("/api/events").handler(this::handleCreateEvent);
        router.post("/api/events/advanced").handler(this::handleCreateAdvancedEvent);
        router.get("/api/events/:eventId").handler(this::handleGetEventStatus);
        router.get("/api/events/queue-info").handler(this::handleGetQueueInfo);
        router.post("/api/events/retry-callback/:eventId").handler(this::handleRetryCallback);
        router.post("/api/events/performance-test").handler(this::handlePerformanceTest);

        // 정적 리소스 설정 (웹 인터페이스)
        router.route("/*").handler(StaticHandler.create("static").setIndexPage("index.html"));

        // HTTP 서버 시작
        int port = config().getInteger("http.port", 8080);
        server.requestHandler(router).listen(port, ar -> {
            if (ar.succeeded()) {
                log.info("HTTP 서버 시작됨: 포트 = {}", port);
                startPromise.complete();
            } else {
                log.error("HTTP 서버 시작 실패", ar.cause());
                startPromise.fail(ar.cause());
            }
        });
    }

    /**
     * 서버 정보 조회 처리
     */
    private void handleGetServerInfo(RoutingContext ctx) {
        JsonObject info = new JsonObject()
                .put("serverId", serverId)
                .put("serverHost", serverHost)
                .put("serverInfo", "Vert.x 4.4.6 on " + serverHost);

        ctx.response()
                .putHeader("content-type", "application/json")
                .end(info.encode());
    }

    /**
     * 이벤트 생성 처리 (기본 버전)
     */
    private void handleCreateEvent(RoutingContext ctx) {
        String eventType = ctx.request().getParam("eventType");
        String payload = ctx.request().getParam("payload");

        if (eventType == null || payload == null) {
            ctx.response()
                    .setStatusCode(400)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                            .put("error", "eventType과 payload는 필수 파라미터입니다.")
                            .encode());
            return;
        }

        JsonObject requestData = new JsonObject()
                .put("eventType", eventType)
                .put("payload", payload);

        vertx.eventBus().request("event.create", requestData, reply -> {
            if (reply.succeeded()) {
                JsonObject event = (JsonObject) reply.result().body();
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(event.encode());
            } else {
                ctx.response()
                        .setStatusCode(500)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject()
                                .put("error", "이벤트 생성 실패: " + reply.cause().getMessage())
                                .encode());
            }
        });
    }

    /**
     * 이벤트 생성 처리 (확장 버전)
     */
    private void handleCreateAdvancedEvent(RoutingContext ctx) {
        String eventType = ctx.request().getParam("eventType");
        String payload = ctx.request().getParam("payload");

        if (eventType == null || payload == null) {
            ctx.response()
                    .setStatusCode(400)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject()
                            .put("error", "eventType과 payload는 필수 파라미터입니다.")
                            .encode());
            return;
        }

        String notificationUrl = ctx.request().getParam("notificationUrl");
        String messageGroupId = ctx.request().getParam("messageGroupId");
        String deduplicationId = ctx.request().getParam("deduplicationId");

        // 콜백 헤더 파싱
        JsonObject callbackHeaders = new JsonObject();
        ctx.request().params().forEach((key, value) -> {
            if (key.startsWith("callbackHeaders[") && key.endsWith("]")) {
                String headerName = key.substring(16, key.length() - 1);
                callbackHeaders.put(headerName, value);
            }
        });

        // 숫자 파라미터 파싱
        int maxRetries = Integer.parseInt(ctx.request().getParam("maxRetries", "3"));
        long retryDelayMs = Long.parseLong(ctx.request().getParam("retryDelayMs", "5000"));
        long visibilityTimeoutMs = Long.parseLong(ctx.request().getParam("visibilityTimeoutMs", "30000"));

        JsonObject requestData = new JsonObject()
                .put("eventType", eventType)
                .put("payload", payload);

        if (notificationUrl != null) {
            requestData.put("notificationUrl", notificationUrl);
        }

        if (callbackHeaders.size() > 0) {
            requestData.put("callbackHeaders", callbackHeaders);
        }

        if (messageGroupId != null) {
            requestData.put("messageGroupId", messageGroupId);
        }

        if (deduplicationId != null) {
            requestData.put("deduplicationId", deduplicationId);
        }

        requestData.put("maxRetries", maxRetries);
        requestData.put("retryDelayMs", retryDelayMs);
        requestData.put("visibilityTimeoutMs", visibilityTimeoutMs);

        vertx.eventBus().request("event.create.advanced", requestData, reply -> {
            if (reply.succeeded()) {
                JsonObject event = (JsonObject) reply.result().body();
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(event.encode());
            } else {
                ctx.response()
                        .setStatusCode(500)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject()
                                .put("error", "고급 이벤트 생성 실패: " + reply.cause().getMessage())
                                .encode());
            }
        });
    }

    /**
     * 이벤트 상태 조회 처리
     */
    private void handleGetEventStatus(RoutingContext ctx) {
        String eventId = ctx.pathParam("eventId");

        vertx.eventBus().request("event.status", eventId, reply -> {
            if (reply.succeeded()) {
                JsonObject event = (JsonObject) reply.result().body();
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(event.encode());
            } else {
                ctx.response()
                        .setStatusCode(404)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject()
                                .put("error", "이벤트를 찾을 수 없음: " + eventId)
                                .encode());
            }
        });
    }

    /**
     * 큐 상태 조회 처리
     */
    private void handleGetQueueInfo(RoutingContext ctx) {
        vertx.eventBus().request("event.queue.info", null, queueReply -> {
            if (queueReply.succeeded()) {
                JsonObject queueInfo = (JsonObject) queueReply.result().body();

                // 통계 정보 조회
                vertx.eventBus().request("event.statistics", null, statsReply -> {
                    if (statsReply.succeeded()) {
                        JsonObject stats = (JsonObject) statsReply.result().body();

                        // 결과 병합
                        JsonObject result = queueInfo.copy();
                        result.put("processedEvents", stats.getLong("processedEvents", 0L));
                        result.put("failedEvents", stats.getLong("failedEvents", 0L));
                        result.put("callbackFailedEvents", stats.getLong("callbackFailedEvents", 0L));
                        result.put("statistics", String.format(
                                "서버: %s, 처리된 이벤트: %d, 실패한 이벤트: %d, 콜백 실패: %d",
                                stats.getString("serverId"),
                                stats.getLong("processedEvents", 0L),
                                stats.getLong("failedEvents", 0L),
                                stats.getLong("callbackFailedEvents", 0L)));

                        ctx.response()
                                .putHeader("content-type", "application/json")
                                .end(result.encode());
                    } else {
                        // 통계 정보 없이 큐 정보만 반환
                        ctx.response()
                                .putHeader("content-type", "application/json")
                                .end(queueInfo.encode());
                    }
                });
            } else {
                ctx.response()
                        .setStatusCode(500)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject()
                                .put("error", "큐 정보 조회 실패: " + queueReply.cause().getMessage())
                                .encode());
            }
        });
    }

    /**
     * 콜백 재시도 처리
     */
    private void handleRetryCallback(RoutingContext ctx) {
        String eventId = ctx.pathParam("eventId");

        vertx.eventBus().request("event.retry.callback", eventId, reply -> {
            if (reply.succeeded()) {
                JsonObject result = (JsonObject) reply.result().body();
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(result.encode());
            } else {
                int statusCode = 500;
                if (reply.cause().getMessage().contains("찾을 수 없음")) {
                    statusCode = 404;
                } else if (reply.cause().getMessage().contains("상태가 아닌")) {
                    statusCode = 400;
                }

                ctx.response()
                        .setStatusCode(statusCode)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject()
                                .put("success", false)
                                .put("message", reply.cause().getMessage())
                                .encode());
            }
        });
    }

    /**
     * 성능 테스트 처리
     */
    private void handlePerformanceTest(RoutingContext ctx) {
        int count = Integer.parseInt(ctx.request().getParam("count", "1000"));
        String eventType = ctx.request().getParam("eventType", "NOTIFICATION");

        log.info("성능 테스트 시작: 이벤트 수={}, 유형={}", count, eventType);

        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);

        for (int i = 0; i < count; i++) {
            final int index = i;
            String payload = String.format("{\"index\": %d, \"message\": \"테스트 메시지\", \"timestamp\": %d}",
                    index, System.currentTimeMillis());

            JsonObject requestData = new JsonObject()
                    .put("eventType", eventType)
                    .put("payload", payload);

            vertx.eventBus().request("event.create", requestData, reply -> {
                completedCount.incrementAndGet();

                if (reply.succeeded()) {
                    successCount.incrementAndGet();
                }

                // 모든 요청이 완료되면 결과 반환
                if (completedCount.get() == count) {
                    long endTime = System.currentTimeMillis();
                    long duration = endTime - startTime;

                    // 큐 정보 조회
                    vertx.eventBus().request("event.queue.info", null, queueReply -> {
                        JsonObject queueInfo = queueReply.succeeded()
                                ? (JsonObject) queueReply.result().body()
                                : new JsonObject();

                        // 통계 정보 조회
                        vertx.eventBus().request("event.statistics", null, statsReply -> {
                            JsonObject stats = statsReply.succeeded()
                                    ? (JsonObject) statsReply.result().body()
                                    : new JsonObject();

                            // 결과 생성
                            JsonObject result = new JsonObject()
                                    .put("requestedCount", count)
                                    .put("successCount", successCount.get())
                                    .put("durationMs", duration)
                                    .put("eventsPerSecond", count * 1000.0 / duration)
                                    .put("queueSize", queueInfo.getInteger("queueSize", 0))
                                    .put("processedEvents", stats.getLong("processedEvents", 0L))
                                    .put("failedEvents", stats.getLong("failedEvents", 0L));

                            log.info("성능 테스트 완료: 소요 시간={}ms, 초당 이벤트={}",
                                    duration, count * 1000.0 / duration);

                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(result.encode());
                        });
                    });
                }
            });
        }
    }
}
