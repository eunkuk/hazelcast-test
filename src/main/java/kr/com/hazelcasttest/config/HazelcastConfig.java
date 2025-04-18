package kr.com.hazelcasttest.config;

import com.hazelcast.config.*;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 헤이즐캐스트 설정 클래스
 * 다중 서버 환경에서 이벤트 위임 및 큐를 위한 헤이즐캐스트 설정을 정의합니다.
 */
@Configuration
public class HazelcastConfig {

    @Value("${hazelcast.cluster.name}")
    private String clusterName;

    @Value("${hazelcast.network.port}")
    private int port;

    @Value("${hazelcast.network.join.multicast.enabled}")
    private boolean multicastEnabled;

    /**
     * 헤이즐캐스트 인스턴스 빈 생성
     * 분산 이벤트 처리 및 큐를 위한 헤이즐캐스트 인스턴스를 설정합니다.
     *
     * @return 설정된 헤이즐캐스트 인스턴스
     */
    @Bean
    public HazelcastInstance hazelcastInstance() {
        Config config = new Config();
        config.setClusterName(clusterName);

        // 네트워크 설정
        NetworkConfig networkConfig = config.getNetworkConfig();
        networkConfig.setPort(port);
        networkConfig.setPortAutoIncrement(true);

        // 멀티캐스트 설정
        JoinConfig joinConfig = networkConfig.getJoin();
        joinConfig.getMulticastConfig().setEnabled(multicastEnabled);

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

        // 분산 토픽 설정 (이벤트 발행용)
        TopicConfig eventTopicConfig = new TopicConfig();
        eventTopicConfig.setName("eventTopic");
        config.addTopicConfig(eventTopicConfig);

        return Hazelcast.newHazelcastInstance(config);
    }
}
