package com.example.backend.service;

import com.example.backend.common.exception.CustomException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.security.JwtTokenProvider;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.dto.TokenResponseDto;
import com.example.backend.entity.Member;
import com.example.backend.enums.Role;
import com.example.backend.repository.MemberRepository;
import com.example.backend.repository.RegionRepository;
import com.example.backend.repository.TokenRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;


import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private RegionRepository regionRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    // RedisTemplate 대신  토큰 레포지토리 인터페이스를 모킹합니다.
    @Mock
    private TokenRepository tokenRepository;

    @InjectMocks
    private AuthService authService;

    @Nested
    @DisplayName("로그인 (login)")
    class Login {

        @Test
        @DisplayName("성공: 토큰을 반환하고 리프레시 토큰을 저장한다")
        void login_success() {
            String email = "test@test.com";
            String password = "password123";
            Member member = Member.builder()
                    .id(1L).email(email).password("encoded").role(Role.ROLE_USER).build();
            TokenResponseDto tokenDto = TokenResponseDto.builder()
                    .accessToken("at").refreshToken("rt").build();

            when(memberRepository.findByEmail(email)).thenReturn(Optional.of(member));
            when(passwordEncoder.matches(password, member.getPassword())).thenReturn(true);
            when(jwtTokenProvider.createTokenSet(1L, email, "ROLE_USER")).thenReturn(tokenDto);

            TokenResponseDto result = authService.login(email, password);

            assertThat(result.getAccessToken()).isEqualTo("at");
            verify(tokenRepository).saveRefreshToken(eq(email), any(TokenResponseDto.class));
        }

        @Test
        @DisplayName("실패: 비밀번호가 일치하지 않으면 INVALID_PASSWORD 예외 발생")
        void login_fail_invalid_password() {
            String email = "test@test.com";
            Member member = Member.builder().email(email).password("encoded").build();

            when(memberRepository.findByEmail(email)).thenReturn(Optional.of(member));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> authService.login(email, "wrong-password"))
                    .isInstanceOf(CustomException.class)
                    .hasMessageContaining(ErrorCode.INVALID_PASSWORD.getMessage());
        }
    }

    @Nested
    @DisplayName("회원가입 (register)")
    class Register {

        @Test
        @DisplayName("실패: 존재하지 않는 지역 ID인 경우 REGION_NOT_FOUND 예외 발생")
        void register_fail_invalid_region() {
            RegisterRequest request = new RegisterRequest();
            ReflectionTestUtils.setField(request, "email", "test@test.com");
            ReflectionTestUtils.setField(request, "regionId", 999L);

            when(memberRepository.existsByEmail(anyString())).thenReturn(false);
            when(regionRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.register(request))
                    .isInstanceOf(CustomException.class)
                    .hasMessageContaining(ErrorCode.REGION_NOT_FOUND.getMessage());
        }
    }

    @Nested
    @DisplayName("토큰 재발급 (reissue)")
    class Reissue {

        @Test
        @DisplayName("실패: 저장된 토큰과 일치하지 않으면 EXPIRED_TOKEN 예외 발생")
        void reissue_fail_token_mismatch() {
            String inputToken = "wrong-token";
            String email = "test@test.com";

            doNothing().when(jwtTokenProvider).validateTokenOrThrow(inputToken);
            when(jwtTokenProvider.getUserEmail(inputToken)).thenReturn(email);

            doThrow(new CustomException(ErrorCode.EXPIRED_TOKEN))
                    .when(tokenRepository).validateRefreshToken(email, inputToken);

            assertThatThrownBy(() -> authService.reissue(inputToken))
                    .isInstanceOf(CustomException.class)
                    .hasMessageContaining(ErrorCode.EXPIRED_TOKEN.getMessage());
        }
    }

    @Nested
    @DisplayName("로그아웃 (logout)")
    class Logout {

        @Test
        @DisplayName("성공: RT 삭제 및 블랙리스트 등록")
        void logout_success() {
            String accessToken = "access-token";
            String email = "test@test.com";
            when(jwtTokenProvider.getUserEmail(accessToken)).thenReturn(email);
            when(jwtTokenProvider.getExpiration(accessToken)).thenReturn(3600L);

            authService.logout(accessToken);

            verify(tokenRepository).deleteRefreshToken(email);
            verify(tokenRepository).addToBlacklist(eq(accessToken), anyLong());
        }
    }
}
