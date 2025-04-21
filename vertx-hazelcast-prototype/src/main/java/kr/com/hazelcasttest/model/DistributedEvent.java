package kr.com.hazelcasttest.model;

/**
 * 호환성을 위한 별칭 클래스
 * kr.com.hazelcasttest.vertx.model.DistributedEvent 클래스를 가리키는 별칭입니다.
 * Hazelcast 직렬화 과정에서 발생하는 ClassNotFoundException 오류를 해결합니다.
 */
public class DistributedEvent extends kr.com.hazelcasttest.vertx.model.DistributedEvent {
    private static final long serialVersionUID = 1L;
}
