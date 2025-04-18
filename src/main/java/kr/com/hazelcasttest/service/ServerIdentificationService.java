package kr.com.hazelcasttest.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * 서버 식별 서비스
 * 다중 서버 환경에서 각 서버를 고유하게 식별하기 위한 서비스입니다.
 */
@Service
@Slf4j
public class ServerIdentificationService {

    @Value("${server.port}")
    private int serverPort;

    @Getter
    private String serverId;

    @Getter
    private String serverHost;

    /**
     * 서비스 초기화 시 서버 ID 생성
     */
    @PostConstruct
    public void init() {
        try {
            // 호스트명 가져오기
            serverHost = InetAddress.getLocalHost().getHostName();

            // 서버 ID 생성 (호스트명 + 포트 + 랜덤 UUID)
            serverId = serverHost + ":" + serverPort + "-" + UUID.randomUUID().toString().substring(0, 8);

            log.info("서버 ID 생성: {}", serverId);
        } catch (UnknownHostException e) {
            // 호스트명을 가져올 수 없는 경우 랜덤 ID 생성
            serverId = "unknown-" + serverPort + "-" + UUID.randomUUID().toString().substring(0, 8);
            serverHost = "unknown";
            log.warn("호스트명을 가져올 수 없습니다. 랜덤 ID 생성: {}", serverId);
        }
    }

    /**
     * 서버 정보 문자열 반환
     *
     * @return 서버 정보 문자열
     */
    public String getServerInfo() {
        return "Server ID: " + serverId + ", Host: " + serverHost + ", Port: " + serverPort;
    }
}
