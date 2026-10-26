package com.rideshare.backend.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Spy
    private EmailTemplateService templateService = new EmailTemplateService();

    @InjectMocks
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailService, "mailHost", "smtp.gmail.com");
        ReflectionTestUtils.setField(emailService, "mailPort", 587);
        ReflectionTestUtils.setField(emailService, "mailUsername", "test@gmail.com");
        ReflectionTestUtils.setField(emailService, "mailPassword", "test-app-password");
        ReflectionTestUtils.setField(emailService, "mailFrom", "test@gmail.com");
        ReflectionTestUtils.setField(emailService, "mailFromName", "RideShare");
        ReflectionTestUtils.setField(emailService, "mailEnabled", true);
    }

    @Test
    void testIsConfigured_ReturnsTrueWhenAllPresent() {
        assertTrue(emailService.isConfigured());
    }

    @Test
    void testIsConfigured_ReturnsFalseWhenCredentialsMissing() {
        ReflectionTestUtils.setField(emailService, "mailUsername", "");
        assertFalse(emailService.isConfigured());

        ReflectionTestUtils.setField(emailService, "mailUsername", "test@gmail.com");
        ReflectionTestUtils.setField(emailService, "mailPassword", "");
        assertFalse(emailService.isConfigured());
    }

    @Test
    void testInvalidRecipient_FailsGracefully() {
        assertFalse(emailService.sendPlainTextEmail("", "Subject", "Body"));
        assertFalse(emailService.sendPlainTextEmail(null, "Subject", "Body"));
        assertFalse(emailService.sendPlainTextEmail("invalid-email", "Subject", "Body"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void testSendPlainTextEmail_Success() {
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        boolean result = emailService.sendPlainTextEmail("rider@example.com", "Test Subject", "Hello World");

        assertTrue(result);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void testSendPlainTextEmail_HandlesSmtpException() {
        doThrow(new MailSendException("SMTP connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        boolean result = emailService.sendPlainTextEmail("rider@example.com", "Test Subject", "Hello World");

        assertFalse(result);
    }

    @Test
    void testSendHtmlEmail_Success() {
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        boolean result = emailService.sendHtmlEmail(
                "rider@example.com",
                "HTML Subject",
                "<h1>Hello</h1>",
                "Hello"
        );

        assertTrue(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testDiagnostics_DoesNotExposePassword() {
        Map<String, Object> diagnostics = emailService.getDiagnostics();

        assertNotNull(diagnostics);
        Map<String, Object> smtp = (Map<String, Object>) diagnostics.get("smtp");
        assertEquals("smtp.gmail.com", smtp.get("host"));
        assertEquals(587, smtp.get("port"));
        assertTrue((Boolean) diagnostics.get("configured"));
        assertFalse(diagnostics.containsKey("password"));
        assertFalse(diagnostics.containsKey("mailPassword"));
        // Check that from address is masked
        String fromAddress = (String) diagnostics.get("fromAddress");
        assertNotNull(fromAddress);
        assertTrue(fromAddress.contains("***"));
    }

    @Test
    void testTemplateService_EscapesXssCharacters() {
        String unsafeInput = "<script>alert('xss')</script>";
        String html = templateService.buildBookingConfirmedTemplate(
                unsafeInput,
                101L,
                "Origin",
                "Destination",
                250.0,
                "Driver",
                LocalDateTime.now()
        );

        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void testOtpEmailDispatch_DoesNotThrow() {
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        assertDoesNotThrow(() -> {
            emailService.sendOtpEmail("chiranjeevi@test.com", "654321", "SIGNUP", 10);
        });
    }

    @Test
    void testFallbackDisabled_DoesNotCallSmtpWhenBrevoFails() {
        BrevoEmailService mockBrevo = mock(BrevoEmailService.class);
        when(mockBrevo.isConfigured()).thenReturn(true);
        when(mockBrevo.sendTransactionalEmail(any(), any(), any(), any(), any()))
                .thenReturn(new BrevoEmailService.BrevoSendResult(false, null, "Key not found", 401));

        ReflectionTestUtils.setField(emailService, "brevoEmailService", mockBrevo);
        ReflectionTestUtils.setField(emailService, "fallbackEnabled", false);

        boolean result = emailService.sendPlainTextEmail("rider@example.com", "Test", "Content");

        assertFalse(result);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void testFallbackEnabled_CallsSmtpWhenBrevoReturns401() {
        BrevoEmailService mockBrevo = mock(BrevoEmailService.class);
        when(mockBrevo.isConfigured()).thenReturn(true);
        when(mockBrevo.sendTransactionalEmail(any(), any(), any(), any(), any()))
                .thenReturn(new BrevoEmailService.BrevoSendResult(false, null, "Key not found", 401));

        ReflectionTestUtils.setField(emailService, "brevoEmailService", mockBrevo);
        ReflectionTestUtils.setField(emailService, "fallbackEnabled", true);
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        boolean result = emailService.sendPlainTextEmail("rider@example.com", "Test", "Content");

        assertTrue(result);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }
}
