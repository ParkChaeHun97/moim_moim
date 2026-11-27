package com.example.backend.entity;

import com.example.backend.common.exception.CustomException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.enums.MemberStatus;
import com.example.backend.enums.Role;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // 회원 고유 ID

    @Column(nullable = false, length = 50)
    private String nickname; // 닉네임

    @Column(unique = true, nullable = false, length = 100)
    private String email; // 이메일 (유니크 제약)

    @Column(nullable = false, length = 255)
    private String password; // 암호화된 비밀번호

    private Integer age; // 나이

    @Column(columnDefinition = "TEXT")
    private String introduction; // 자기소개

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private Region region; // 거주지역 (FK)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    private Integer points; // 활동 포인트
    private Integer level; // 레벨

    @Column(name = "profile_image", length = 255)
    private String profileImage; // 프로필 이미지 URL

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "ENUM('ACTIVE', 'INACTIVE', 'BANNED')")
    private MemberStatus status; // 계정 상태

    private LocalDateTime lastLogin; // 마지막 로그인



    // ROLE용 메서드
    public Member(String email, String password, Role role) {
        this.email = email;
        this.password = password;
        this.role = (role == null) ? Role.ROLE_USER : role;
    }

    // Member Builder
    public static Member createNewMember(RegisterRequest request,
                                         String encodedPassword,
                                         Region region) {
        return Member.builder()
                .email(request.getEmail())
                .password(encodedPassword)
                .nickname(request.getNickname())
                .age(request.getAge())
                .introduction(request.getBio())
                .region(region)
                .role(Role.ROLE_USER)      // 기본값 캡슐화
                .status(MemberStatus.ACTIVE) // 기본값 캡슐화
                .points(0)                 // 기본값 캡슐화
                .level(1)                  // 기본값 캡슐화
                .build();
    }

    public void validatePassword(PasswordEncoder passwordEncoder, String rawPassword) {
        if (!passwordEncoder.matches(rawPassword, this.password)) {
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }
    }



}