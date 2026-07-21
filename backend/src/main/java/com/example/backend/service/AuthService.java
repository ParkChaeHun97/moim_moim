package com.example.backend.service;

import com.example.backend.common.exception.CustomException;
import com.example.backend.common.exception.ErrorCode;
import com.example.backend.common.security.JwtTokenProvider;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.dto.TokenResponseDto;
import com.example.backend.entity.Member;
import com.example.backend.entity.Region;
import com.example.backend.repository.MemberRepository;
import com.example.backend.repository.RegionRepository;
import com.example.backend.repository.TokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;
    private final RegionRepository regionRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final TokenRepository tokenRepository;

    @Transactional
    public TokenResponseDto login(String email, String password) {

        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));


        member.validatePassword(passwordEncoder, password);

        TokenResponseDto tokenDto = jwtTokenProvider.createTokenSet(
                member.getId(), email, member.getRole().name());

        tokenRepository.saveRefreshToken(email, tokenDto);

        return tokenDto;
    }

    @Transactional
    public void register(RegisterRequest request) {

        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        Region region = regionRepository.findById(request.getRegionId())
                .orElseThrow(() -> new CustomException(ErrorCode.REGION_NOT_FOUND));
        Member member = Member.createNewMember(request, passwordEncoder.encode(request.getPassword()), region);

        memberRepository.save(member);
        log.info("회원가입 완료 - Email: {}", request.getEmail());
    }

    @Transactional
    public TokenResponseDto reissue(String refreshToken) {

        // 1. Refresh Token 검증
        jwtTokenProvider.validateTokenOrThrow(refreshToken);

        // 2. 이메일 추출 및 Redis 저장값 확인
        String email = jwtTokenProvider.getUserEmail(refreshToken);

        tokenRepository.validateRefreshToken(email, refreshToken);

        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 5. 새로운 토큰 세트 생성 (ID 포함)
        TokenResponseDto newTokenSet = jwtTokenProvider.createTokenSet(
                member.getId(), member.getEmail(), member.getRole().name()
        );

        // 6. Redis 리프레시 토큰 갱신
        tokenRepository.saveRefreshToken(email, newTokenSet);

        return newTokenSet;
    }

    @Transactional
    public void logout(String accessToken) {

        String email = jwtTokenProvider.getUserEmail(accessToken);
        Long expiration = jwtTokenProvider.getExpiration(accessToken);

        // Redis 처리
        tokenRepository.deleteRefreshToken(email);
        tokenRepository.addToBlacklist(accessToken, expiration);
    }
}