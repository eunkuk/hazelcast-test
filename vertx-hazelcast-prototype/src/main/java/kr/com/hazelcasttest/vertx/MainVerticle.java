package kr.com.hazelcasttest.vertx;

import com.hazelcast.config.*;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import lombok.extern.slf4j.Slf4j;

/**
 * 메인 버티클
 * Vert.x 애플리케이션의 진입점으로, Hazelcast 클러스터 설정 및 다른 버티클 배포를 담당합니다.
 */
@Slf4j
public class MainVerticle extends AbstractVerticle {

    public static void main(String[] args) {
        // Hazelcast 클러스터 설정
        Config hazelcastConfig = createHazelcastConfig();
        ClusterManager clusterManager = new HazelcastClusterManager(hazelcastConfig);

        // Vert.x 옵션 설정
        VertxOptions options = new VertxOptions()
                .setClusterManager(clusterManager)
                .setEventLoopPoolSize(8) // 이벤트 루프 스레드 수
                .setWorkerPoolSize(16);  // 워커 스레드 수

        // 클러스터링된 Vert.x 인스턴스 생성
        Vertx.clusteredVertx(options, res -> {
            if (res.succeeded()) {
                Vertx vertx = res.result();

                // 메인 버티클 배포
                vertx.deployVerticle(new MainVerticle(), ar -> {
                    if (ar.succeeded()) {
                        log.info("메인 버티클 배포 성공: {}", ar.result());
                    } else {
                        log.error("메인 버티클 배포 실패", ar.cause());
                    }
                });
            } else {
                log.error("Vert.x 클러스터 생성 실패", res.cause());
            }
        });
    }

    @Override
    public void start(Promise<Void> startPromise) {
        log.info("메인 버티클 시작");

        // 설정 로드
        JsonObject config = config();
        int httpPort = config.getInteger("http.port", 8080);

        // API 버티클 배포
        DeploymentOptions apiOptions = new DeploymentOptions()
                .setConfig(config)
                .setInstances(2); // API 버티클 인스턴스 수

        vertx.deployVerticle(ApiVerticle.class.getName(), apiOptions, apiDeployment -> {
            if (apiDeployment.succeeded()) {
                log.info("API 버티클 배포 성공: {}", apiDeployment.result());

                // 이벤트 생산자 버티클 배포
                vertx.deployVerticle(EventProducerVerticle.class.getName(), producerDeployment -> {
                    if (producerDeployment.succeeded()) {
                        log.info("이벤트 생산자 버티클 배포 성공: {}", producerDeployment.result());

                        // 이벤트 소비자 버티클 배포 (워커 버티클로 배포)
                        DeploymentOptions consumerOptions = new DeploymentOptions()
                                .setWorker(true) // 워커 버티클로 설정
                                .setInstances(5); // 소비자 버티클 인스턴스 수

                        vertx.deployVerticle(EventConsumerVerticle.class.getName(), consumerOptions, consumerDeployment -> {
                            if (consumerDeployment.succeeded()) {
                                log.info("이벤트 소비자 버티클 배포 성공: {}", consumerDeployment.result());
                                startPromise.complete();
                            } else {
                                log.error("이벤트 소비자 버티클 배포 실패", consumerDeployment.cause());
                                startPromise.fail(consumerDeployment.cause());
                            }
                        });
                    } else {
                        log.error("이벤트 생산자 버티클 배포 실패", producerDeployment.cause());
                        startPromise.fail(producerDeployment.cause());
                    }
                });
            } else {
                log.error("API 버티클 배포 실패", apiDeployment.cause());
                startPromise.fail(apiDeployment.cause());
            }
        });
    }

    /**
     * Hazelcast 설정 생성
     * 분산 이벤트 처리를 위한 Hazelcast 설정을 정의합니다.
     *
     * @return Hazelcast 설정
     */
    private static Config createHazelcastConfig() {
        Config config = new Config();
        config.setClusterName("hazelcast-cluster");

        // 네트워크 설정
        NetworkConfig networkConfig = config.getNetworkConfig();
        networkConfig.setPort(5700);
        networkConfig.setPortAutoIncrement(true);

        // 멀티캐스트 설정
        JoinConfig joinConfig = networkConfig.getJoin();
        joinConfig.getMulticastConfig().setEnabled(true);

        // 분산 이벤트 큐 설정
        QueueConfig eventQueueConfig = new QueueConfig();
        eventQueueConfig.setName("eventQueue");
        eventQueueConfig.setBackupCount(1);
        eventQueueConfig.setMaxSize(10000);
        config.addQueueConfig(eventQueueConfig);

        // 분산 맵 설정 (이벤트 상태 저장용)
        MapConfig eventMapConfig = new MapConfig();
        eventMapConfig.setName("eventMap");
        eventMapConfig.setBackupCount(1);
        eventMapConfig.setTimeToLiveSeconds(0); // 무제한
        config.addMapConfig(eventMapConfig);

        // 콜백 재시도 큐 설정
        QueueConfig callbackRetryQueueConfig = new QueueConfig();
        callbackRetryQueueConfig.setName("callbackRetryQueue");
        callbackRetryQueueConfig.setBackupCount(1);
        callbackRetryQueueConfig.setMaxSize(10000);
        config.addQueueConfig(callbackRetryQueueConfig);

        // 통계 맵 설정
        MapConfig statsMapConfig = new MapConfig();
        statsMapConfig.setName("eventStatisticsMap");
        statsMapConfig.setBackupCount(1);
        statsMapConfig.setTimeToLiveSeconds(0); // 무제한
        config.addMapConfig(statsMapConfig);

        return config;
    }
}
