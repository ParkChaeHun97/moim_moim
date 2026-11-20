package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AccessTokenResponse {
    private final String accessToken;

    public static AccessTokenResponse from(TokenResponseDto dto) {
        return new AccessTokenResponse(dto.getAccessToken());
    }
}
