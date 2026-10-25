package com.rideshare.backend;

import com.rideshare.backend.dto.*;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.service.AuthService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AuthIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    private static String testEmail = "testuser_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    private static String testPassword = "Password123!";
    private static String jwtToken;

    @Test
    @Order(1)
    public void testRegisterNewUser() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Test Rider");
        request.setEmail(testEmail);
        request.setPassword(testPassword);
        request.setPhoneNumber("+1555" + (int)(Math.random() * 9000000 + 1000000));
        request.setRole("RIDER");

        ResponseEntity<?> response = authService.register(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("requiresVerification"));
    }

    @Test
    @Order(2)
    public void testDuplicateRegistrationFails() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Duplicate Rider");
        request.setEmail(testEmail);
        request.setPassword(testPassword);

        ResponseEntity<?> response = authService.register(request);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    @Order(3)
    public void testLoginWithInvalidCredentialsFails() {
        LoginRequest request = new LoginRequest();
        request.setEmail(testEmail);
        request.setPassword("WrongPassword!");

        ResponseEntity<?> response = authService.login(request);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @Order(4)
    public void testLoginUnverifiedUserReturnsForbidden() {
        LoginRequest request = new LoginRequest();
        request.setEmail(testEmail);
        request.setPassword(testPassword);

        ResponseEntity<?> response = authService.login(request);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertEquals(true, body.get("requiresVerification"));
    }

    @Test
    @Order(5)
    public void testVerifyValidOtpAndLogin() {
        // In database, simulate successful verification
        User user = userRepository.findByEmail(testEmail);
        assertNotNull(user);
        user.setVerified(true);
        userRepository.save(user);

        LoginRequest request = new LoginRequest();
        request.setEmail(testEmail);
        request.setPassword(testPassword);

        ResponseEntity<?> response = authService.login(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertNotNull(body.get("token"));
        jwtToken = (String) body.get("token");

        AuthResponse userDto = (AuthResponse) body.get("user");
        assertNotNull(userDto);
        assertEquals(testEmail, userDto.getEmail());
    }

    @Test
    @Order(6)
    public void testGetMeWithJwtToken() {
        assertNotNull(jwtToken);
        ResponseEntity<?> response = authService.getCurrentUser("Bearer " + jwtToken);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        AuthResponse me = (AuthResponse) response.getBody();
        assertNotNull(me);
        assertEquals(testEmail, me.getEmail());
    }

    @Test
    @Order(7)
    public void testForgotPasswordGenericResponse() {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setIdentifier(testEmail);

        ResponseEntity<?> response = authService.forgotPassword(request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map body = (Map) response.getBody();
        assertTrue(((String) body.get("message")).contains("password reset code has been sent"));
    }

    @Test
    @Order(8)
    public void testResendOtpCooldownEnforcement() {
        ResendOtpRequest request = new ResendOtpRequest();
        request.setIdentifier(testEmail);
        request.setPurpose("PASSWORD_RESET");

        // First resend might succeed or trigger cooldown depending on previous
        authService.resendOtp(request);

        // Immediate subsequent resend MUST trigger 429 Too Many Requests
        ResponseEntity<?> response2 = authService.resendOtp(request);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response2.getStatusCode());
    }
}
