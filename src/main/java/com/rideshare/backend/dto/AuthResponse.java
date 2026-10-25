package com.rideshare.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private Long id;
    private String name;
    private String email;
    private String phoneNumber;
    private String role;
    private String referralCode;
    private String avatarUrl;
    private boolean verified;
    private List<String> permissions;
}

