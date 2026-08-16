package com.example.backend.controller;


import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.dto.TokenResponseDto;
import com.example.backend.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "회원가입이 완료되었습니다!"));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponseDto> login(@RequestBody LoginRequest loginRequest, HttpServletResponse response) {
        // 1. 서비스에서 토큰 세트(AT, RT) 생성
        // (실제 구현 시에는 userService 등을 통해 role을 조회해와야 합니다)
        TokenResponseDto tokenDto = authService.login(loginRequest.getEmail(), loginRequest.getPassword());

        // 2. Refresh Token을 담을 쿠키 생성 (보안 강화)
        // 프론트엔드는 nginx/vite 프록시를 통해 항상 API와 동일 출처로 통신하므로
        // 크로스 사이트 쿠키 전송(SameSite=None)이 필요 없다 — Strict로 CSRF 방어.
        ResponseCookie refreshTokenCookie = ResponseCookie.from("refreshToken", tokenDto.getRefreshToken())
                .httpOnly(true)    // JavaScript에서 접근 불가 (XSS 방지)
                .secure(true)      // HTTPS에서만 전송
                .path("/")         // 모든 경로에서 유효
                .maxAge(tokenDto.getRefreshTokenExpirationTime() / 1000) // 초 단위 설정
                .sameSite("Strict") // CSRF 방지
                .build();

        // 3. 응답 헤더에 쿠키 추가
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString());

        // 4. Access Token 정보만 바디에 담아 반환
        // 클라이언트는 이 JSON을 받아 Authorization 헤더에 Bearer로 사용합니다.
        return ResponseEntity.ok(tokenDto);
    }

    @PostMapping("/reissue")
    public ResponseEntity<?> reissue(@CookieValue(name = "refreshToken") String refreshToken, HttpServletResponse response) {
        // 쿠키에서 꺼낸 RT로 재발급 수행
        TokenResponseDto tokenDto = authService.reissue(refreshToken);

        //새로 발급된 RT로 쿠키를 다시 만들기 (동일 출처 통신이므로 SameSite=Strict)
        ResponseCookie refreshTokenCookie = ResponseCookie.from("refreshToken", tokenDto.getRefreshToken())
                .httpOnly(true)
                .secure(true) // 로컬 테스트 시 false 고려
                .path("/")
                .maxAge(tokenDto.getRefreshTokenExpirationTime() / 1000)
                .sameSite("Strict")
                .build();

        // 3. 응답 헤더에 새 쿠키 설정 (기존 쿠키 덮어쓰기)
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString());

        return ResponseEntity.ok(tokenDto);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader("Authorization") String bearerToken,
            HttpServletResponse response) {

        // 1. "Bearer " 접두어 제거
        String accessToken = bearerToken.substring(7);

        // 2. 서비스 로직 수행 (Redis 처리)
        authService.logout(accessToken);

        // 3. 클라이언트의 Refresh Token 쿠키 무효화
        ResponseCookie cookie = ResponseCookie.from("refreshToken", "")
                .maxAge(0) // 즉시 만료
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.ok("로그아웃 되었습니다.");
    }

    /**
     * [버그 수정] 토큰 유효성 검증 전용 엔드포인트
     * 프론트엔드 App.js 초기 구동 시 호출하여 변조된 토큰을 걸러냄
     */
    @GetMapping("/verify")
    public ResponseEntity<Void> verifyToken() {
        // Filter를 통과해 이 컨트롤러에 도달했다는 것은 유효한 토큰임을 보장함
        return ResponseEntity.ok().build();
    }

}
