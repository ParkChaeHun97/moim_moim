package com.example.backend.repository;

import com.example.backend.entity.MeetingPost;
import com.example.backend.entity.Participation;
import com.example.backend.enums.ParticipationStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParticipationRepository extends JpaRepository<Participation, Long> {
    // 특정 모임에 특정 유저가 이미 참여 중인지 확인하는 메소드 (중복 참여 방지용)
    boolean existsByMemberIdAndMeetingPostId(Long memberId, Long meetingPostId);

    // 특정 모임에서 승인(APPROVED)된 참여자 수 조회
    long countByMeetingPostAndStatus(MeetingPost meetingPost, ParticipationStatus status);

    @Query("SELECT p FROM Participation p " +
            "JOIN FETCH p.member " + // 💡 Participation을 가져올 때 Member까지 한 번에!
            "WHERE p.meetingPost.id = :postId")
    List<Participation> findAllByMeetingPostId(@Param("postId") Long postId);

    // MeetingPost 락을 걸기 위해 필요한 meetingPostId만 락 없이 가볍게 조회
    @Query("SELECT p.meetingPost.id FROM Participation p WHERE p.id = :id")
    Optional<Long> findMeetingPostIdById(@Param("id") Long id);

    // 참여 신청 승인/거절 처리 시 동일 신청 중복 처리 방지용 비관적 락
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT p FROM Participation p WHERE p.id = :id")
    Optional<Participation> findByIdForUpdate(@Param("id") Long id);

}
