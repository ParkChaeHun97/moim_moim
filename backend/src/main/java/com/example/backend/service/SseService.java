package com.example.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SseService {
    // 유저별 Emitter 관리 (Thread-safe)
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private static final Long DEFAULT_TIMEOUT = 60L * 1000 * 60; // 1시간

    public SseEmitter subscribe(Long memberId) {
        // 1. 기존 연결이 있으면 종료 (중복 연결 및 메모리 누수 방지)
        if (emitters.containsKey(memberId)) {
            log.info("기존 SSE 연결을 종료합니다. memberId: {}", memberId);
            emitters.get(memberId).complete();
            emitters.remove(memberId);
        }

        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        emitters.put(memberId, emitter);

        // 2. 생명주기 리스너 등록
        emitter.onCompletion(() -> {
            log.info("SSE 연결이 완료되었습니다. memberId: {}", memberId);
            emitters.remove(memberId);
        });
        emitter.onTimeout(() -> {
            log.info("SSE 연결 시간이 초과되었습니다. memberId: {}", memberId);
            emitters.remove(memberId);
        });
        emitter.onError((e) -> {
            log.error("SSE 연결 중 오류가 발생했습니다. memberId: {}", memberId, e);
            emitters.remove(memberId);
        });

        // 3. 503 에러 방지용 더미 데이터 전송
        send(memberId, "connect", "Connected [userId=" + memberId + "]");

        return emitter;
    }

    public void send(Long memberId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(memberId);

        if (emitter == null) {
            // 접속 중이 아닌 사용자에게 알림을 보내려 할 때 (선택 사항: 예외를 던지거나 로그만 남김)
            log.warn("알림 전송 실패: 연결된 사용자가 없습니다. memberId={}", memberId);
            return;
        }

        try {
            log.info("✅ 전송 시도: memberId={}, data={}", memberId, data);
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException e) {
            // SSE는 best-effort 채널이므로 전송 실패가 호출자(비즈니스 트랜잭션)에 전파되지 않도록 흡수한다.
            emitters.remove(memberId);
            log.warn("SSE 전송 실패로 Emitter 제거: receiverId={}", memberId, e);
        }
    }

    public int getEmittersSize() {
        return emitters.size();
    }
}
