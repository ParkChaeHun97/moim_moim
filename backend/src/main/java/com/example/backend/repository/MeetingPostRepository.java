package com.example.backend.repository;

import com.example.backend.entity.MeetingPost;
import com.example.backend.entity.Participation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MeetingPostRepository extends JpaRepository<MeetingPost, Long> {
    @Query("select m from MeetingPost m join fetch m.creator join fetch m.category where m.id = :id")
    Optional<MeetingPost> findByIdWithDetails(@Param("id") Long id);


    // 최신 생성일 순으로 전체 조회
    // N+1 방지를 위한 fetch join
    @Query("select m from MeetingPost m join fetch m.category join fetch m.creator order by m.createdAt desc")
    List<MeetingPost> findAllWithDetails();

    // 최신순 정렬 (기본) + 카테고리 필터 선택
    @Query("select m from MeetingPost m join fetch m.category join fetch m.creator " +
            "where (:categoryId is null or m.category.id = :categoryId) " +
            "order by m.createdAt desc")
    List<MeetingPost> findAllOrderByLatest(@Param("categoryId") Long categoryId);

    // 마감임박순(startDate 오름차순) + 카테고리 필터 선택
    @Query("select m from MeetingPost m join fetch m.category join fetch m.creator " +
            "where (:categoryId is null or m.category.id = :categoryId) " +
            "order by m.startDate asc")
    List<MeetingPost> findAllOrderByClosing(@Param("categoryId") Long categoryId);

    // 인기순(viewCount 내림차순) + 카테고리 필터 선택
    @Query("select m from MeetingPost m join fetch m.category join fetch m.creator " +
            "where (:categoryId is null or m.category.id = :categoryId) " +
            "order by m.viewCount desc")
    List<MeetingPost> findAllOrderByPopular(@Param("categoryId") Long categoryId);

    // [특수 정렬] 잔여석 적은 순 (capacity - currentParticipants)
    @Query("SELECT m FROM MeetingPost m " +
            "JOIN FETCH m.category " +   // ✅ 카테고리 정보 한방에 가져오기
            "JOIN FETCH m.creator " +    // ✅ 작성자(Member) 정보 한방에 가져오기
            "WHERE (:categoryId IS NULL OR m.category.id = :categoryId) " +
            "ORDER BY (m.capacity - m.currentParticipants) ASC")
    List<MeetingPost> findAllOrderByUrgent(@Param("categoryId") Long categoryId);

    // 💡 내가 만든 모임 찾기 (최신순)
    List<MeetingPost> findByCreatorIdOrderByCreatedAtDesc(Long creatorId);


    @Query("SELECT p FROM Participation p " +
            "JOIN FETCH p.meetingPost mp " +
            "JOIN FETCH mp.category " +
            "JOIN FETCH mp.creator " +
            "WHERE p.member.id = :memberId " +
            "AND mp.creator.id != :memberId " + // 방장이 본인인 경우는 제외하는 조건
            "ORDER BY mp.createdAt DESC")
    List<Participation> findAllAppliedByMemberId(@Param("memberId") Long memberId);

}