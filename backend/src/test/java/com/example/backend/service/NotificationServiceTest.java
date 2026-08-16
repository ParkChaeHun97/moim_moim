package com.example.backend.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Optional;

import com.example.backend.common.exception.CustomException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.dto.NotificationResponse;
import com.example.backend.entity.Member;
import com.example.backend.entity.Notification;
import com.example.backend.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @InjectMocks
    private NotificationService notificationService;

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private SseService sseService;

    // 가상의 데이터 생성 헬퍼 메서드
    private Member createMember(Long id) {
        return Member.builder().id(id).nickname("user" + id).build();
    }

    private Notification createNotification(Long id, Member receiver) {
        Notification notification = Notification.builder()
                .receiver(receiver)
                .content("테스트 알림")
                .url("/test")
                // .isRead(false) // 기본값이 false라면 생략 가능
                .build();

        // Reflection을 통해 private 필드인 id에 강제로 값 주입
        ReflectionTestUtils.setField(notification, "id", id);

        return notification;
    }

    @Nested
    @DisplayName("알림 생성 (createNotification)")
    class CreateNotification {

        @Test
        @DisplayName("성공: 알림을 생성하고 저장한다")
        void createNotification_Success() {
            // given
            Member receiver = createMember(1L);

            // when
            notificationService.createNotification(receiver, "새로운 신청!", "/mypage");

            // then
            verify(notificationRepository, times(1)).save(any(Notification.class));
        }
    }

    @Nested
    @DisplayName("알림 목록 조회 (getMyNotifications)")
    class GetMyNotifications {

        @Test
        @DisplayName("성공: 내 알림 목록을 최신순으로 반환한다")
        void getMyNotifications_Success() {
            // given
            Long memberId = 1L;
            Member receiver = createMember(memberId);
            Notification n1 = createNotification(101L, receiver);
            Notification n2 = createNotification(102L, receiver);

            given(notificationRepository.findByReceiverIdOrderByCreatedAtDesc(memberId))
                    .willReturn(List.of(n2, n1));

            // when
            List<NotificationResponse> result = notificationService.getMyNotifications(memberId);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isEqualTo(102L);
        }
    }

    @Nested
    @DisplayName("읽음 처리 (markAsRead)")
    class MarkAsRead {

        @Test
        @DisplayName("성공: 본인의 알림인 경우 읽음 처리된다")
        void markAsRead_Success() {
            // given
            Long memberId = 1L;
            Member receiver = createMember(memberId);
            Notification notification = createNotification(100L, receiver);

            given(notificationRepository.findById(100L)).willReturn(Optional.of(notification));

            // when
            notificationService.markAsRead(100L, memberId);

            // then
            assertThat(notification.isRead()).isTrue();
        }

        @Test
        @DisplayName("실패: 다른 사람의 알림을 읽으려 하면 NOT_AUTHORIZED_NOTIFICATION 예외 발생")
        void markAsRead_Fail_NotAuthorized() {
            // given
            Long myId = 1L;
            Long otherId = 2L;
            Member otherMember = createMember(otherId);
            Notification othersNotification = createNotification(100L, otherMember);

            given(notificationRepository.findById(100L)).willReturn(Optional.of(othersNotification));

            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(100L, myId))
                    .isInstanceOf(CustomException.class)
                    .hasMessageContaining(ErrorCode.NOT_AUTHORIZED_NOTIFICATION.getMessage());
        }

        @Test
        @DisplayName("실패: 알림이 존재하지 않으면 NOTIFICATION_NOT_FOUND 예외 발생")
        void markAsRead_Fail_NotFound() {
            // given
            given(notificationRepository.findById(anyLong())).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> notificationService.markAsRead(999L, 1L))
                    .isInstanceOf(CustomException.class)
                    .hasMessageContaining(ErrorCode.NOTIFICATION_NOT_FOUND.getMessage());
        }
    }

    @Nested
    @DisplayName("알림 삭제 (deleteNotification)")
    class DeleteNotification {

        @Test
        @DisplayName("성공: 본인의 알림인 경우 삭제된다")
        void deleteNotification_Success() {
            // given
            Long memberId = 1L;
            Member receiver = createMember(memberId);
            Notification notification = createNotification(100L, receiver);

            given(notificationRepository.findById(100L)).willReturn(Optional.of(notification));

            // when
            notificationService.deleteNotification(100L, memberId);

            // then
            verify(notificationRepository, times(1)).delete(notification);
        }
    }
}
