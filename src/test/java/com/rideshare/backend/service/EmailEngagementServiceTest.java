package com.rideshare.backend.service;

import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class EmailEngagementServiceTest {

    @Mock
    private EmailAudienceContactRepository contactRepository;

    @Mock
    private EmailCampaignRepository campaignRepository;

    @Mock
    private EmailWorkflowRepository workflowRepository;

    @Mock
    private EmailWorkflowExecutionRepository workflowExecutionRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private EmailLogRepository emailLogRepository;

    @Mock
    private PlatformSettingsRepository platformSettingsRepository;

    @InjectMocks
    private EmailEngagementService engagementService;

    @BeforeEach
    void setUp() {
        when(contactRepository.save(any(EmailAudienceContact.class))).thenAnswer(i -> {
            EmailAudienceContact c = i.getArgument(0);
            if (c.getId() == null) c.setId(101L);
            return c;
        });
    }

    @Test
    void testGetPresetTemplates_ContainsAllRequiredCampaigns() {
        List<Map<String, Object>> presets = engagementService.getPresetTemplates();
        assertNotNull(presets);
        assertEquals(7, presets.size(), "Must provide all 7 professional RideShare campaigns");

        Set<String> campaignIds = new HashSet<>();
        for (Map<String, Object> p : presets) {
            campaignIds.add((String) p.get("id"));
            assertTrue(p.containsKey("compiledHtml"));
            String html = (String) p.get("compiledHtml");
            assertTrue(html.contains("RideShare"));
            assertTrue(html.contains("Ride Together, Go Further"));
        }

        assertTrue(campaignIds.contains("COMING_SOON"));
        assertTrue(campaignIds.contains("PASSENGER_ACCESS"));
        assertTrue(campaignIds.contains("DRIVER_REGISTRATION"));
        assertTrue(campaignIds.contains("NEW_USER_WELCOME"));
        assertTrue(campaignIds.contains("PRODUCT_UPDATE"));
        assertTrue(campaignIds.contains("APP_LAUNCH"));
        assertTrue(campaignIds.contains("RE_ENGAGEMENT"));
    }

    @Test
    void testCompileBlocksToHtml_TokenSubstitution() {
        String templateJson = "[{\"type\":\"hero\",\"title\":\"Hello {{firstName}}\",\"subtitle\":\"Role: {{audienceType}}\"}]";
        Map<String, String> tokens = Map.of(
                "firstName", "Karan",
                "audienceType", "Passenger"
        );

        String html = engagementService.compileBlocksToHtml(templateJson, tokens);
        assertNotNull(html);
        assertTrue(html.contains("Hello Karan"));
        assertTrue(html.contains("Role: Passenger"));
    }

    @Test
    void testAddOrUpdateContact_ValidatesEmail() {
        assertThrows(IllegalArgumentException.class, () -> {
            engagementService.addOrUpdateContact("invalid-email", "John Doe", null, "Bengaluru", "PASSENGER", "TEST", true);
        });

        EmailAudienceContact contact = engagementService.addOrUpdateContact(
                "john.doe@example.com", "John Doe", "+919876543210", "Bengaluru", "PASSENGER", "WAITLIST_FORM", true
        );
        assertNotNull(contact);
        assertEquals("john.doe@example.com", contact.getEmail());
        assertEquals("PASSENGER", contact.getAudienceType());
        assertEquals("SUBSCRIBED", contact.getStatus());
    }

    @Test
    void testUnsubscribeContactByToken_SuccessfullyMarksUnsubscribed() {
        String token = UUID.randomUUID().toString();
        EmailAudienceContact contact = EmailAudienceContact.builder()
                .id(1L)
                .email("subscriber@example.com")
                .status("SUBSCRIBED")
                .unsubscribeToken(token)
                .build();

        when(contactRepository.findByUnsubscribeToken(token)).thenReturn(Optional.of(contact));

        boolean unsubscribed = engagementService.unsubscribeContactByToken(token, "Too many emails");
        assertTrue(unsubscribed);
        assertEquals("UNSUBSCRIBED", contact.getStatus());
        assertEquals("Too many emails", contact.getUnsubscribeReason());
        assertNotNull(contact.getUnsubscribedAt());
    }

    @Test
    void testWorkflowExecution_DeduplicationPreventsDuplicateEmails() {
        EmailWorkflow workflow = EmailWorkflow.builder()
                .id(1L)
                .name("Welcome Email")
                .triggerType("WAITLIST_SIGNUP")
                .targetAudience("ALL")
                .subject("Welcome to RideShare")
                .templateDataJson("[{\"type\":\"hero\",\"title\":\"Welcome\"}]")
                .enabled(true)
                .totalTriggered(0)
                .totalSent(0)
                .build();

        EmailAudienceContact contact = EmailAudienceContact.builder()
                .id(10L)
                .email("commuter@example.com")
                .status("SUBSCRIBED")
                .unsubscribeToken("token-123")
                .totalEmailsReceived(0)
                .build();

        when(workflowRepository.findFirstByTriggerTypeAndEnabledTrue("WAITLIST_SIGNUP"))
                .thenReturn(Optional.of(workflow));
        when(workflowExecutionRepository.existsByWorkflowIdAndContactEmail(1L, "commuter@example.com"))
                .thenReturn(true); // Already executed

        engagementService.executeWorkflowForContact("WAITLIST_SIGNUP", contact);

        // Should NOT send email since it was already executed
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
