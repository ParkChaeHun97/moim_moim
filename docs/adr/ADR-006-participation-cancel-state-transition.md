# ADR-00X: 참가 취소 처리 방식 — 상태 전이

## Context
참가 취소 기능 구현 시 두 가지 방식을 고려했다:
1. Participation row 삭제
2. status를 CANCELLED로 전이 (soft delete)

초기에는 삭제 방식을 검토했으나, ParticipationStatus enum에 이미
CANCELLED, WAITING이 정의되어 있었고 승인/거절 로직(updateParticipationStatus)이
이미 상태 전이 패턴으로 구현되어 있음을 확인했다.

## Decision
상태 전이 방식을 채택한다. Participation은 삭제하지 않고 status만
CANCELLED로 변경하며, ACCEPTED 상태였던 경우에만 MeetingPost의
currentParticipants를 감소시킨다.

## Consequences
- 취소 이력이 보존되어 추후 통계, 이의제기 대응이 가능하다.
- 기존 중복 참여 체크(existsByMemberIdAndMeetingPostId)가 상태 무관하게
  존재 여부만 확인하므로, CANCELLED 상태에서 재신청을 허용하려면
  이 메서드에 상태 조건을 추가해야 한다 (미해결, 재신청 정책 미정으로 보류).
- 신청자 명단 조회(getParticipants)가 CANCELLED/REJECTED까지 포함해서
  반환할 수 있으므로, 모임장 화면에 노출 여부를 정책적으로 결정해야 한다 (보류).

## Revisit trigger
- 재신청 허용 정책이 결정되면 existsBy 쿼리 조건 수정
- 강퇴 기능 도입 시 CANCELLED와의 상태 전이 규칙(멱등성 가드) 재검토
- 리뷰/평점 도입 시 CANCELLED 참가자를 평가 대상에서 제외하는 필터링 로직 추가