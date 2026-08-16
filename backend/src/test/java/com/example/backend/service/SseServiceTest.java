package com.example.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SseServiceTest {

    private SseService sseService;

    @BeforeEach
    void setUp() {
        sseService = new SseService();
    }

    @Nested
    @DisplayName("구독 (subscribe)")
    class Subscribe {

        @Test
        @DisplayName("성공: 새로운 Emitter가 생성되고 맵의 크기가 증가해야 한다")
        void subscribe_Success() {
            // given
            Long memberId = 1L;

            // when
            SseEmitter result = sseService.subscribe(memberId);

            // then
            assertNotNull(result);
            assertEquals(1, sseService.getEmittersSize());
        }

        @Test
        @DisplayName("중복 구독 시 기존 연결은 종료(교체)되고 맵의 크기는 1로 유지되어야 한다")
        void subscribe_Duplicate_ShouldReplaceOldOne() {
            // given
            Long memberId = 1L;
            SseEmitter oldEmitter = sseService.subscribe(memberId);
            assertEquals(1, sseService.getEmittersSize());

            // when
            SseEmitter newEmitter = sseService.subscribe(memberId);

            // then
            assertNotSame(oldEmitter, newEmitter);
            assertNotNull(newEmitter);
            assertEquals(1, sseService.getEmittersSize());
        }

        @Test
        @DisplayName("딥 테스트: 100명의 사용자가 동시에 구독해도 데이터 유실 없이 정확히 저장되어야 한다")
        void subscribe_Concurrency_100Users_Test() throws InterruptedException {
            // given
            int threadCount = 100;
            ExecutorService executorService = Executors.newFixedThreadPool(32);
            CountDownLatch latch = new CountDownLatch(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                long memberId = i;
                executorService.submit(() -> {
                    try {
                        sseService.subscribe(memberId);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();

            // then
            assertEquals(threadCount, sseService.getEmittersSize());
            executorService.shutdown();
        }

        @Test
        @DisplayName("딥 테스트: 동일 사용자가 동시에 여러 번 구독 요청을 보낼 때 최종적으로 1개만 남아야 한다")
        void subscribe_Concurrency_SameUser_Test() throws InterruptedException {
            // given
            int threadCount = 50;
            Long memberId = 1L;
            ExecutorService executorService = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                executorService.submit(() -> {
                    try {
                        sseService.subscribe(memberId);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();

            // then
            assertEquals(1, sseService.getEmittersSize()); // 동시 요청에도 단 하나만 남음으로써 누수 방지 증명
            executorService.shutdown();
        }
    }

    @Nested
    @DisplayName("데이터 전송 (send)")
    class Send {

        @Test
        @DisplayName("전송 시 예외가 발생하지 않아야 하며, 미연결 유저 전송도 안전해야 한다")
        void send_Test() {
            // given
            Long memberId = 1L;
            sseService.subscribe(memberId);

            // when & then
            // 1. 연결된 유저에게 전송
            assertDoesNotThrow(() ->
                    sseService.send(memberId, "testEvent", "testData")
            );

            // 2. 존재하지 않는 유저에게 전송 시에도 안전하게 무시되는지 확인
            assertDoesNotThrow(() ->
                    sseService.send(999L, "testEvent", "testData")
            );
        }
    }
}
