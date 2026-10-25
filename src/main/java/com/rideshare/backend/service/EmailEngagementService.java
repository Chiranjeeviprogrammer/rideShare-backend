package com.rideshare.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class EmailEngagementService {

    private static final Logger logger = LoggerFactory.getLogger(EmailEngagementService.class);
    private static final Pattern EMAIL_REGEX = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    public static final String DEFAULT_HTTPS_IMAGE_BASE = "https://cultural-giddiness-pueblo.ngrok-free.dev";

    private String resolveImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_HTTPS_IMAGE_BASE + "/images/city-and-car-banner.jpg";
        }
        if (url.startsWith("/")) {
            return DEFAULT_HTTPS_IMAGE_BASE + url;
        }
        return url;
    }

    private String resolveLogoUrl(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_HTTPS_IMAGE_BASE + "/images/logo.png";
        }
        if (url.startsWith("/")) {
            return DEFAULT_HTTPS_IMAGE_BASE + url;
        }
        return url;
    }

    @Autowired
    private EmailAudienceContactRepository contactRepository;

    @Autowired
    private EmailCampaignRepository campaignRepository;

    @Autowired
    private EmailWorkflowRepository workflowRepository;

    @Autowired
    private EmailWorkflowExecutionRepository workflowExecutionRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private PlatformSettingsRepository platformSettingsRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initPresets() {
        try {
            initSampleWorkflows();
            initSampleContactsIfEmpty();
        } catch (Exception e) {
            logger.warn("Non-critical initialization of engagement presets skipped: {}", e.getMessage());
        }
    }

    // ==========================================
    // 1. TEMPLATE DEFINITIONS & COMPILER
    // ==========================================

    public List<Map<String, Object>> getPresetTemplates() {
        List<Map<String, Object>> list = new ArrayList<>();

        list.add(createTemplatePreset(
                "COMING_SOON",
                "RideShare Coming Soon",
                "RideShare Coming Soon: Smarter, Safer Daily Commuting",
                "Join thousands of commuters preparing for a smarter, cleaner way to travel together.",
                "Join the Waitlist",
                "https://rideshare.com/waitlist",
                "COMING_SOON",
                "Join the verified carpool network connecting trusted professionals and daily commuters on identical routes.",
                Arrays.asList(
                        Map.of("title", "100% ID Verified", "desc", "Every driver and passenger is verified with government ID before their first trip."),
                        Map.of("title", "Save Up to 70%", "desc", "Split actual fuel and toll costs fairly without predatory surge pricing."),
                        Map.of("title", "Reduced Carbon Footprint", "desc", "Take cars off the road and travel sustainably every single day.")
                )
        ));

        list.add(createTemplatePreset(
                "PASSENGER_ACCESS",
                "Passenger Early Access",
                "Your Early Access Invitation: Reserve Your Seat with RideShare",
                "You are invited to test early commute routes with verified drivers in your city.",
                "Get Early Access",
                "https://rideshare.com/early-access",
                "PASSENGER_ACCESS",
                "As an early subscriber, you get priority route matching, zero booking fees during pilot month, and direct community support.",
                Arrays.asList(
                        Map.of("title", "Guaranteed Seat", "desc", "Reserve your daily commute seat days in advance with reliable schedules."),
                        Map.of("title", "Direct Doorstep Routes", "desc", "Commute with coworkers and neighbors heading your direction."),
                        Map.of("title", "Cashless & Secure", "desc", "In-app automated cost-splitting with real-time GPS tracking.")
                )
        ));

        list.add(createTemplatePreset(
                "DRIVER_REGISTRATION",
                "Driver Registration & Benefits",
                "Turn Your Daily Commute Into Reliable Fuel Savings",
                "Drive your existing route, choose your passengers, and offset your monthly car expenses.",
                "Become a Driver",
                "https://rideshare.com/become-driver",
                "DRIVER_REGISTRATION",
                "You drive to work anyway. Why commute alone with empty seats when you can share the journey with verified professionals?",
                Arrays.asList(
                        Map.of("title", "Cover 100% Fuel Expenses", "desc", "Offset petrol, diesel, and maintenance costs without going out of your way."),
                        Map.of("title", "Full Schedule Control", "desc", "Publish only the dates and times you want. Accept passengers you approve."),
                        Map.of("title", "Automated Direct Payouts", "desc", "Receive automated weekly or immediate bank deposits straight to your account.")
                )
        ));

        list.add(createTemplatePreset(
                "NEW_USER_WELCOME",
                "New User Welcome",
                "Welcome to RideShare: The Verified Commuter Community",
                "Your waitlist registration is confirmed. Here is what to expect next.",
                "Discover RideShare",
                "https://rideshare.com/about",
                "NEW_USER_WELCOME",
                "Thank you for joining RideShare. We are building the most trusted carpooling ecosystem to make urban mobility affordable, sustainable, and stress-free.",
                Arrays.asList(
                        Map.of("title", "Step 1: Waitlist Priority", "desc", "Your position is secured. We notify members neighborhood by neighborhood."),
                        Map.of("title", "Step 2: Profile Completion", "desc", "You will receive an invite to complete your optional preferences."),
                        Map.of("title", "Step 3: Launch Ride Credit", "desc", "Early waitlist members receive inaugural ride credits upon city launch.")
                )
        ));

        list.add(createTemplatePreset(
                "PRODUCT_UPDATE",
                "Product Engineering Update",
                "What We Built This Month: Enhanced Safety & Route Matching",
                "A transparent look at the features and safety benchmarks our team has completed.",
                "Read Full Changelog",
                "https://rideshare.com/updates",
                "PRODUCT_UPDATE",
                "We believe in building in public with our early community. Here are the core safety and booking milestones delivered this month.",
                Arrays.asList(
                        Map.of("title", "Real-Time Driver Telemetry", "desc", "Active GPS heartbeat monitoring ensures passenger safety and accurate ETAs."),
                        Map.of("title", "Multi-Language Support", "desc", "Seamless localized interface in English and Hindi for riders and drivers."),
                        Map.of("title", "Automated OTP Verification", "desc", "Fast, high-deliverability email and phone authentication to prevent spam.")
                )
        ));

        list.add(createTemplatePreset(
                "APP_LAUNCH",
                "App Launch Announcement",
                "RideShare is Officially Live! Download & Book Your First Ride",
                "The wait is over. Experience verified, affordable carpooling starting today.",
                "Download the App",
                "https://rideshare.com/download",
                "APP_LAUNCH",
                "We are thrilled to officially launch RideShare. Commuting will never be the same. Download the app today on iOS and Android.",
                Arrays.asList(
                        Map.of("title", "iOS & Android Available", "desc", "Native, battery-efficient mobile application with instant notifications."),
                        Map.of("title", "Verified Network Ready", "desc", "Over 500+ active routes scheduled across key business and tech corridors."),
                        Map.of("title", "24/7 Support Dedicated", "desc", "Our in-house operations team is standing by to assist with any trip.")
                )
        ));

        list.add(createTemplatePreset(
                "RE_ENGAGEMENT",
                "Subscriber Re-engagement",
                "We Miss You on RideShare: See What's New on Your Route",
                "New routes and drivers have been added in your area since your last visit.",
                "Explore Active Rides",
                "https://rideshare.com/explore",
                "RE_ENGAGEMENT",
                "It has been a while since we connected. New commuter groups have formed on popular routes, and we would love to have you travel with us.",
                Arrays.asList(
                        Map.of("title", "Updated Timelines", "desc", "Flexible morning and evening pickup windows to fit hybrid work schedules."),
                        Map.of("title", "Manage Email Preferences", "desc", "Easily adjust how often you receive updates or pause notifications anytime."),
                        Map.of("title", "Zero Commitment", "desc", "Check matching routes anytime without recurring commitments or fees.")
                )
        ));

        return list;
    }

    private Map<String, Object> createTemplatePreset(
            String id,
            String name,
            String defaultSubject,
            String defaultPreviewText,
            String ctaText,
            String ctaUrl,
            String campaignType,
            String heroDescription,
            List<Map<String, String>> highlights
    ) {
        Map<String, Object> preset = new HashMap<>();
        preset.put("id", id);
        preset.put("name", name);
        preset.put("campaignType", campaignType);
        preset.put("defaultSubject", defaultSubject);
        preset.put("defaultPreviewText", defaultPreviewText);

        List<Map<String, Object>> blocks = new ArrayList<>();

        // 1. Branded Header with Logo & Tagline
        blocks.add(Map.of(
                "type", "header",
                "logoUrl", DEFAULT_HTTPS_IMAGE_BASE + "/images/logo.png",
                "logoText", "RideShare",
                "tagline", "Ride Together, Go Further",
                "logoBadge", "OFFICIAL MOBILITY"
        ));

        // 2. Full-Width Hero Banner with City and Car Background Image
        blocks.add(Map.of(
                "type", "hero",
                "imageUrl", DEFAULT_HTTPS_IMAGE_BASE + "/images/city-and-car-hero-overlay.jpg",
                "badge", "PRE-LAUNCH COMMUNITY",
                "title", "Your Journey Starts Together",
                "subtitle", "Discover a smarter way to commute. Ride Together, Go Further.",
                "ctaText", "Stay Connected with RideShare",
                "ctaUrl", ctaUrl
        ));

        // 3. Welcome Message & Commute Purpose
        blocks.add(Map.of(
                "type", "text",
                "content", heroDescription
        ));

        // 4. Feature Highlights Grid
        blocks.add(Map.of(
                "type", "highlights",
                "title", "Why Commuters Choose RideShare",
                "items", highlights
        ));

        // 5. Trust / Safety Seal
        blocks.add(Map.of(
                "type", "trust_badge",
                "badgeText", "100% Identity & Vehicle Verified Community",
                "subtext", "Zero spam. Strict background and driving document verification for your peace of mind."
        ));

        // 6. Pre-Launch App Availability Banner
        blocks.add(Map.of(
                "type", "app_links",
                "title", "Available Soon on iOS & Android",
                "subtext", "Our native mobile apps are currently in private review. You will receive an early invite upon public availability."
        ));

        // 7. Footer with Required Unsubscribe & Address
        blocks.add(Map.of(
                "type", "footer",
                "companyAddress", "RideShare Mobility Technologies, Bengaluru, India",
                "unsubscribeNote", "You received this email because you subscribed or joined the waitlist for RideShare.",
                "unsubscribeUrl", "{{unsubscribeUrl}}"
        ));

        preset.put("blocks", blocks);

        try {
            String json = objectMapper.writeValueAsString(blocks);
            preset.put("templateDataJson", json);
            preset.put("compiledHtml", compileBlocksToHtml(json, Map.of(
                    "firstName", "Valued Commuter",
                    "audienceType", "Passenger",
                    "unsubscribeUrl", "https://rideshare.com/unsubscribe?demo=1",
                    "year", String.valueOf(LocalDateTime.now().getYear())
            )));
        } catch (Exception e) {
            preset.put("compiledHtml", "<p>Error compiling template</p>");
        }

        return preset;
    }

    /**
     * Compiles block JSON into bulletproof, responsive HTML compatible with major email clients.
     */
    public String compileBlocksToHtml(String templateDataJson, Map<String, String> tokens) {
        if (templateDataJson == null || templateDataJson.isBlank()) {
            return "<div style=\"color: #FFFFFF; font-family: sans-serif;\">Empty email content</div>";
        }

        List<Map<String, Object>> blocks;
        try {
            blocks = objectMapper.readValue(templateDataJson, new TypeReference<>() {});
        } catch (Exception e) {
            logger.error("Failed to parse template JSON: {}", e.getMessage());
            return "<div style=\"color: #EF4444; font-family: sans-serif;\">Invalid template format</div>";
        }

        StringBuilder bodyHtml = new StringBuilder();

        for (Map<String, Object> block : blocks) {
            String type = (String) block.getOrDefault("type", "text");
            switch (type) {
                case "header":
                    bodyHtml.append(renderHeaderBlock(block, tokens));
                    break;
                case "hero":
                    bodyHtml.append(renderHeroBlock(block, tokens));
                    break;
                case "text":
                    bodyHtml.append(renderTextBlock(block, tokens));
                    break;
                case "button":
                    bodyHtml.append(renderButtonBlock(block, tokens));
                    break;
                case "highlights":
                    bodyHtml.append(renderHighlightsBlock(block, tokens));
                    break;
                case "trust_badge":
                    bodyHtml.append(renderTrustBadgeBlock(block, tokens));
                    break;
                case "app_links":
                    bodyHtml.append(renderAppLinksBlock(block, tokens));
                    break;
                case "divider":
                    bodyHtml.append("<div style=\"height: 1px; background: rgba(255,255,255,0.08); margin: 24px 0;\"></div>");
                    break;
                case "footer":
                    bodyHtml.append(renderFooterBlock(block, tokens));
                    break;
                default:
                    break;
            }
        }

        return wrapInBaseEmailLayout(bodyHtml.toString(), tokens);
    }

    private String replaceTokens(String text, Map<String, String> tokens) {
        if (text == null) return "";
        String result = text;
        if (tokens != null) {
            for (Map.Entry<String, String> entry : tokens.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
            }
        }
        return result;
    }

    private String renderHeaderBlock(Map<String, Object> b, Map<String, String> tokens) {
        String logoUrl = resolveLogoUrl(replaceTokens((String) b.getOrDefault("logoUrl", DEFAULT_HTTPS_IMAGE_BASE + "/images/logo.png"), tokens));
        String logoText = replaceTokens((String) b.getOrDefault("logoText", "RideShare"), tokens);
        String tagline = replaceTokens((String) b.getOrDefault("tagline", "Ride Together, Go Further"), tokens);
        String badge = replaceTokens((String) b.getOrDefault("logoBadge", "OFFICIAL MOBILITY"), tokens);

        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background: linear-gradient(180deg, #070B14 0%, #0B101D 100%); background-color: #070B14; border-bottom: 1px solid rgba(0, 229, 255, 0.18); padding: 24px 20px 20px;\">\n"
                + "  <tr>\n"
                + "    <td align=\"center\">\n"
                + "      <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin: 0 auto;\">\n"
                + "        <tr>\n"
                + "          <td valign=\"middle\" style=\"padding-right: 12px;\">\n"
                + "            <img src=\"" + HtmlUtils.htmlEscape(logoUrl) + "\" width=\"42\" height=\"42\" alt=\"RideShare Logo\" style=\"display: block; width: 42px; height: 42px; border-radius: 10px; border: 1px solid rgba(0, 229, 255, 0.35);\" />\n"
                + "          </td>\n"
                + "          <td valign=\"middle\">\n"
                + "            <div style=\"font-size: 26px; font-weight: 800; color: #FFFFFF; letter-spacing: -0.5px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\">\n"
                + "              <span style=\"color: #FFFFFF;\">Ride</span><span style=\"color: #00E5FF;\">Share</span>\n"
                + "            </div>\n"
                + "          </td>\n"
                + "        </tr>\n"
                + "      </table>\n"
                + "      <div style=\"display: inline-block; background: rgba(0, 229, 255, 0.10); border: 1px solid rgba(0, 229, 255, 0.28); border-radius: 999px; padding: 3px 12px; margin-top: 10px; font-weight: 700; font-size: 10px; color: #00E5FF; letter-spacing: 1.2px; text-transform: uppercase; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\">\n"
                + HtmlUtils.htmlEscape(badge) + "\n"
                + "      </div>\n"
                + "      <div style=\"font-size: 12px; color: #94A3B8; margin-top: 6px; font-weight: 500; letter-spacing: 0.3px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\">\n"
                + HtmlUtils.htmlEscape(tagline) + "\n"
                + "      </div>\n"
                + "    </td>\n"
                + "  </tr>\n"
                + "</table>";
    }

    private String renderHeroBlock(Map<String, Object> b, Map<String, String> tokens) {
        String imageUrl = resolveImageUrl(replaceTokens((String) b.getOrDefault("imageUrl", DEFAULT_HTTPS_IMAGE_BASE + "/images/city-and-car-hero-overlay.jpg"), tokens));
        String badge = replaceTokens((String) b.getOrDefault("badge", "PRE-LAUNCH COMMUNITY"), tokens);
        String title = replaceTokens((String) b.getOrDefault("title", "Your Journey Starts Together"), tokens);
        String subtitle = replaceTokens((String) b.getOrDefault("subtitle", "Discover a smarter way to commute. Ride Together, Go Further."), tokens);
        String ctaText = replaceTokens((String) b.getOrDefault("ctaText", b.getOrDefault("ctaLabel", "Stay Connected with RideShare")), tokens);
        String ctaUrl = replaceTokens((String) b.getOrDefault("ctaUrl", "https://rideshare.com/waitlist"), tokens);

        StringBuilder sb = new StringBuilder();

        sb.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color: #070B14; border-bottom: 2px solid #00E5FF;\">\n")
          .append("  <tr>\n")
          .append("    <td align=\"center\" style=\"padding: 0;\">\n")
          .append("      <!--[if gte mso 9]>\n")
          .append("      <v:rect xmlns:v=\"urn:schemas-microsoft-com:vml\" fill=\"true\" stroke=\"false\" style=\"width:620px; height:430px;\">\n")
          .append("        <v:fill type=\"frame\" src=\"").append(HtmlUtils.htmlEscape(imageUrl)).append("\" color=\"#0B101D\" />\n")
          .append("        <v:textbox inset=\"0,0,0,0\">\n")
          .append("      <![endif]-->\n")
          .append("      <div style=\"background-color: #0B101D; background-image: linear-gradient(180deg, rgba(7, 11, 20, 0.74) 0%, rgba(7, 11, 20, 0.90) 100%), url('").append(HtmlUtils.htmlEscape(imageUrl)).append("'); background-size: cover; background-position: center top; background-repeat: no-repeat;\">\n")
          .append("        <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">\n")
          .append("          <tr>\n")
          .append("            <td align=\"center\" valign=\"middle\" style=\"padding: 44px 28px 46px; text-align: center; background-color: rgba(7, 11, 20, 0.65);\">\n");

        // Top Brand Mark inside Hero Overlay
        sb.append("              <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin: 0 auto 14px;\">\n")
          .append("                <tr>\n")
          .append("                  <td valign=\"middle\" style=\"padding-right: 10px;\">\n")
          .append("                    <img src=\"").append(HtmlUtils.htmlEscape(resolveImageUrl(DEFAULT_HTTPS_IMAGE_BASE + "/images/logo.png"))).append("\" width=\"32\" height=\"32\" alt=\"RideShare\" style=\"border-radius: 8px; border: 1px solid rgba(0, 229, 255, 0.4); display: block;\" />\n")
          .append("                  </td>\n")
          .append("                  <td valign=\"middle\" align=\"left\">\n")
          .append("                    <div style=\"font-size: 20px; font-weight: 800; color: #FFFFFF; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; letter-spacing: -0.3px; line-height: 1.1;\">\n")
          .append("                      <span style=\"color: #FFFFFF;\">Ride</span><span style=\"color: #00E5FF;\">Share</span>\n")
          .append("                    </div>\n")
          .append("                    <div style=\"font-size: 10px; color: #94A3B8; font-weight: 600; letter-spacing: 0.5px; text-transform: uppercase;\">\n")
          .append("                      Ride Together, Go Further\n")
          .append("                    </div>\n")
          .append("                  </td>\n")
          .append("                </tr>\n")
          .append("              </table>\n");

        if (!badge.isBlank()) {
            sb.append("              <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin: 0 auto 16px;\">\n")
              .append("                <tr>\n")
              .append("                  <td style=\"background: rgba(0, 229, 255, 0.16); border: 1px solid #00E5FF; border-radius: 999px; padding: 5px 16px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; font-size: 11px; font-weight: 800; color: #00E5FF; letter-spacing: 1.5px; text-transform: uppercase;\">\n")
              .append(HtmlUtils.htmlEscape(badge))
              .append("\n                  </td>\n")
              .append("                </tr>\n")
              .append("              </table>\n");
        }

        sb.append("              <h1 style=\"font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; font-size: 28px; font-weight: 800; color: #FFFFFF; line-height: 1.25; margin: 0 auto 14px; max-width: 520px; letter-spacing: -0.5px; text-shadow: 0 2px 12px rgba(0,0,0,0.9);\">\n")
          .append(HtmlUtils.htmlEscape(title))
          .append("\n              </h1>\n");

        if (!subtitle.isBlank()) {
            sb.append("              <p style=\"font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; font-size: 16px; line-height: 1.6; color: #E2E8F0; margin: 0 auto 28px; max-width: 480px; text-shadow: 0 1px 8px rgba(0,0,0,0.95);\">\n")
              .append(HtmlUtils.htmlEscape(subtitle))
              .append("\n              </p>\n");
        }

        if (!ctaText.isBlank()) {
            sb.append("              <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin: 0 auto;\">\n")
              .append("                <tr>\n")
              .append("                  <td align=\"center\" style=\"background: linear-gradient(135deg, #00E5FF 0%, #00B4D8 100%); background-color: #00E5FF; border-radius: 12px; box-shadow: 0 4px 22px rgba(0, 229, 255, 0.45);\">\n")
              .append("                    <a href=\"").append(HtmlUtils.htmlEscape(ctaUrl)).append("\" target=\"_blank\" style=\"display: inline-block; padding: 15px 36px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; font-size: 15px; font-weight: 800; color: #04060A; text-decoration: none; letter-spacing: 0.3px; border-radius: 12px;\">\n")
              .append(HtmlUtils.htmlEscape(ctaText))
              .append("\n                    </a>\n")
              .append("                  </td>\n")
              .append("                </tr>\n")
              .append("              </table>\n");
        }

        sb.append("            </td>\n")
          .append("          </tr>\n")
          .append("        </table>\n")
          .append("      </div>\n")
          .append("      <!--[if gte mso 9]>\n")
          .append("        </v:textbox>\n")
          .append("      </v:rect>\n")
          .append("      <![endif]-->\n")
          .append("    </td>\n")
          .append("  </tr>\n")
          .append("</table>\n");

        return sb.toString();
    }

    private String renderTextBlock(Map<String, Object> b, Map<String, String> tokens) {
        String content = replaceTokens((String) b.getOrDefault("content", ""), tokens);
        return "<div style=\"padding: 10px 24px 16px; font-size: 15px; line-height: 1.65; color: #E2E8F0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\">"
                + HtmlUtils.htmlEscape(content).replace("\n", "<br/>")
                + "</div>";
    }

    private String renderButtonBlock(Map<String, Object> b, Map<String, String> tokens) {
        String label = replaceTokens((String) b.getOrDefault("label", "Learn More"), tokens);
        String url = replaceTokens((String) b.getOrDefault("url", "https://rideshare.com"), tokens);
        String align = (String) b.getOrDefault("alignment", "center");

        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"padding: 16px 24px 28px;\">"
                + "  <tr>"
                + "    <td align=\"" + HtmlUtils.htmlEscape(align) + "\">"
                + "      <a href=\"" + HtmlUtils.htmlEscape(url) + "\" target=\"_blank\" style=\"display: inline-block; background: linear-gradient(135deg, #00E5FF 0%, #00B4D8 100%); color: #070A10; font-size: 15px; font-weight: 800; text-decoration: none; padding: 14px 32px; border-radius: 12px; box-shadow: 0 4px 18px rgba(0, 229, 255, 0.35); text-align: center; letter-spacing: 0.3px;\">"
                + HtmlUtils.htmlEscape(label)
                + "      </a>"
                + "    </td>"
                + "  </tr>"
                + "</table>";
    }

    @SuppressWarnings("unchecked")
    private String renderHighlightsBlock(Map<String, Object> b, Map<String, String> tokens) {
        String title = replaceTokens((String) b.getOrDefault("title", "Core Highlights"), tokens);
        List<Map<String, String>> items = (List<Map<String, String>>) b.get("items");
        if (items == null) items = Collections.emptyList();

        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"padding: 12px 24px 24px;\">");
        if (!title.isBlank()) {
            sb.append("<div style=\"font-size: 16px; font-weight: 700; color: #FFFFFF; margin-bottom: 16px;\">")
              .append(HtmlUtils.htmlEscape(title))
              .append("</div>");
        }

        for (Map<String, String> item : items) {
            String itemTitle = replaceTokens(item.getOrDefault("title", ""), tokens);
            String itemDesc = replaceTokens(item.getOrDefault("desc", ""), tokens);

            sb.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background: #0E1626; border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 12px; margin-bottom: 12px; padding: 14px 16px;\">")
              .append("  <tr>")
              .append("    <td width=\"24\" valign=\"top\" style=\"padding-right: 12px;\">")
              .append("      <div style=\"width: 20px; height: 20px; border-radius: 50%; background: rgba(0, 229, 255, 0.15); color: #00E5FF; font-size: 12px; font-weight: 800; text-align: center; line-height: 20px;\">&#10003;</div>")
              .append("    </td>")
              .append("    <td valign=\"top\">")
              .append("      <div style=\"font-size: 14px; font-weight: 700; color: #FFFFFF; margin-bottom: 3px;\">")
              .append(HtmlUtils.htmlEscape(itemTitle))
              .append("      </div>")
              .append("      <div style=\"font-size: 13px; color: #94A3B8; line-height: 1.5;\">")
              .append(HtmlUtils.htmlEscape(itemDesc))
              .append("      </div>")
              .append("    </td>")
              .append("  </tr>")
              .append("</table>");
        }

        sb.append("</div>");
        return sb.toString();
    }

    private String renderTrustBadgeBlock(Map<String, Object> b, Map<String, String> tokens) {
        String badgeText = replaceTokens((String) b.getOrDefault("badgeText", "100% Verified Community"), tokens);
        String subtext = replaceTokens((String) b.getOrDefault("subtext", "Safe, verified trips every time."), tokens);

        return "<div style=\"padding: 10px 24px 20px;\">"
                + "  <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background: rgba(0, 229, 255, 0.04); border: 1px dashed rgba(0, 229, 255, 0.3); border-radius: 14px; padding: 16px;\">"
                + "    <tr>"
                + "      <td align=\"center\">"
                + "        <div style=\"font-size: 14px; font-weight: 800; color: #00E5FF; margin-bottom: 4px;\">&#128737; " + HtmlUtils.htmlEscape(badgeText) + "</div>"
                + "        <div style=\"font-size: 12px; color: #94A3B8; line-height: 1.4;\">" + HtmlUtils.htmlEscape(subtext) + "</div>"
                + "      </td>"
                + "    </tr>"
                + "  </table>"
                + "</div>";
    }

    private String renderAppLinksBlock(Map<String, Object> b, Map<String, String> tokens) {
        String title = replaceTokens((String) b.getOrDefault("title", "Coming Soon to iOS & Android"), tokens);
        String subtext = replaceTokens((String) b.getOrDefault("subtext", "Currently in pre-launch. No broken downloads."), tokens);

        return "<div style=\"padding: 10px 24px 24px;\">"
                + "  <table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background: #0B111E; border-radius: 12px; border: 1px solid rgba(255,255,255,0.06); padding: 16px;\">"
                + "    <tr>"
                + "      <td align=\"center\">"
                + "        <div style=\"font-size: 13px; font-weight: 700; color: #FFFFFF; margin-bottom: 4px;\">&#128241; " + HtmlUtils.htmlEscape(title) + "</div>"
                + "        <div style=\"font-size: 12px; color: #64748B;\">" + HtmlUtils.htmlEscape(subtext) + "</div>"
                + "      </td>"
                + "    </tr>"
                + "  </table>"
                + "</div>";
    }

    private String renderFooterBlock(Map<String, Object> b, Map<String, String> tokens) {
        String companyAddress = replaceTokens((String) b.getOrDefault("companyAddress", "RideShare Mobility Technologies"), tokens);
        String unsubscribeNote = replaceTokens((String) b.getOrDefault("unsubscribeNote", "You are receiving this because you subscribed to RideShare updates."), tokens);
        String unsubscribeUrl = replaceTokens((String) b.getOrDefault("unsubscribeUrl", "{{unsubscribeUrl}}"), tokens);

        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background: #060910; border-top: 1px solid rgba(255, 255, 255, 0.06); padding: 26px 20px;\">"
                + "  <tr>"
                + "    <td align=\"center\" style=\"font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; font-size: 12px; color: #64748B; line-height: 1.6;\">"
                + "      <div style=\"color: #94A3B8; margin-bottom: 8px; font-weight: 600;\">" + HtmlUtils.htmlEscape(companyAddress) + "</div>"
                + "      <div style=\"margin-bottom: 12px;\">" + HtmlUtils.htmlEscape(unsubscribeNote) + "</div>"
                + "      <div>"
                + "        <a href=\"" + HtmlUtils.htmlEscape(unsubscribeUrl) + "\" target=\"_blank\" style=\"color: #00E5FF; text-decoration: underline; font-weight: 600;\">Unsubscribe from Marketing Emails</a>"
                + "        <span style=\"margin: 0 8px; color: #475569;\">&bull;</span>"
                + "        <a href=\"https://rideshare.com/privacy\" target=\"_blank\" style=\"color: #94A3B8; text-decoration: none;\">Privacy Policy</a>"
                + "        <span style=\"margin: 0 8px; color: #475569;\">&bull;</span>"
                + "        <a href=\"https://rideshare.com/terms\" target=\"_blank\" style=\"color: #94A3B8; text-decoration: none;\">Terms</a>"
                + "      </div>"
                + "      <div style=\"margin-top: 14px; font-size: 11px; color: #475569;\">&copy; " + LocalDateTime.now().getYear() + " RideShare. All rights reserved.</div>"
                + "    </td>"
                + "  </tr>"
                + "</table>";
    }

    private String wrapInBaseEmailLayout(String innerHtml, Map<String, String> tokens) {
        String previewText = tokens != null && tokens.containsKey("previewText") ? tokens.get("previewText") : "";
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "  <meta charset=\"UTF-8\">\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "  <title>RideShare</title>\n"
                + "  <style>\n"
                + "    body { margin: 0; padding: 0; background-color: #04060A; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; -webkit-font-smoothing: antialiased; color: #FFFFFF; }\n"
                + "    table { border-collapse: collapse; }\n"
                + "    img { border: 0; outline: none; text-decoration: none; display: block; }\n"
                + "    @media only screen and (max-width: 620px) {\n"
                + "      .email-container { width: 100% !important; border-radius: 0 !important; }\n"
                + "    }\n"
                + "  </style>\n"
                + "</head>\n"
                + "<body style=\"margin: 0; padding: 0; background-color: #04060A;\">\n"
                + "  <!-- Preheader text hidden from body -->\n"
                + "  <div style=\"display: none; font-size: 1px; color: #04060A; line-height: 1px; max-height: 0px; max-width: 0px; opacity: 0; overflow: hidden;\">\n"
                + HtmlUtils.htmlEscape(previewText)
                + "  </div>\n"
                + "  <center style=\"width: 100%; background-color: #04060A; padding: 24px 0;\">\n"
                + "    <table class=\"email-container\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background-color: #0B101D; border-radius: 16px; overflow: hidden; border: 1px solid rgba(255, 255, 255, 0.08); box-shadow: 0 10px 40px rgba(0,0,0,0.7);\">\n"
                + "      <tr>\n"
                + "        <td>\n"
                + innerHtml
                + "        </td>\n"
                + "      </tr>\n"
                + "    </table>\n"
                + "  </center>\n"
                + "</body>\n"
                + "</html>";
    }

    // ==========================================
    // 2. AUDIENCE & CONTACT MANAGEMENT
    // ==========================================

    @Transactional
    public EmailAudienceContact addOrUpdateContact(
            String email,
            String fullName,
            String phoneNumber,
            String city,
            String audienceType,
            String source,
            Boolean consentGiven
    ) {
        if (email == null || !EMAIL_REGEX.matcher(email.trim()).matches()) {
            throw new IllegalArgumentException("Invalid email address format.");
        }

        String cleanEmail = email.trim().toLowerCase();
        Optional<EmailAudienceContact> existing = contactRepository.findByEmailIgnoreCase(cleanEmail);

        EmailAudienceContact contact;
        if (existing.isPresent()) {
            contact = existing.get();
            if (fullName != null && !fullName.isBlank()) contact.setFullName(fullName.trim());
            if (phoneNumber != null && !phoneNumber.isBlank()) contact.setPhoneNumber(phoneNumber.trim());
            if (city != null && !city.isBlank()) contact.setCity(city.trim());
            if (audienceType != null && !audienceType.isBlank()) contact.setAudienceType(audienceType.toUpperCase());
            // If previously unsubscribed and re-signing up via waitlist with explicit consent
            if ("UNSUBSCRIBED".equalsIgnoreCase(contact.getStatus()) && Boolean.TRUE.equals(consentGiven)) {
                contact.setStatus("SUBSCRIBED");
                contact.setConsentGiven(true);
                contact.setConsentTimestamp(LocalDateTime.now());
                contact.setUnsubscribedAt(null);
                contact.setUnsubscribeReason(null);
            }
        } else {
            contact = EmailAudienceContact.builder()
                    .email(cleanEmail)
                    .fullName(fullName != null ? fullName.trim() : null)
                    .phoneNumber(phoneNumber != null ? phoneNumber.trim() : null)
                    .city(city != null ? city.trim() : null)
                    .audienceType(audienceType != null && !audienceType.isBlank() ? audienceType.toUpperCase() : "PASSENGER")
                    .source(source != null && !source.isBlank() ? source : "WAITLIST_FORM")
                    .status("SUBSCRIBED")
                    .consentGiven(consentGiven != null ? consentGiven : true)
                    .consentTimestamp(LocalDateTime.now())
                    .unsubscribeToken(UUID.randomUUID().toString())
                    .totalEmailsReceived(0)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        EmailAudienceContact saved = contactRepository.save(contact);

        // Check if welcome workflow should be triggered for new signups
        if (existing.isEmpty() && "SUBSCRIBED".equalsIgnoreCase(saved.getStatus())) {
            triggerWorkflowAsync("WAITLIST_SIGNUP", saved);
        }

        return saved;
    }

    @Transactional
    public Map<String, Object> importCsvContacts(String csvContent, String defaultAudienceType) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalArgumentException("CSV content is empty.");
        }

        int added = 0;
        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String line;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Simple comma separated parser
                String[] parts = line.split(",");
                if (isHeader) {
                    isHeader = false;
                    // Check if first line contains header titles
                    if (parts[0].toLowerCase().contains("email")) {
                        continue;
                    }
                }

                String email = parts[0].trim().replace("\"", "");
                String fullName = parts.length > 1 ? parts[1].trim().replace("\"", "") : null;
                String audType = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim().replace("\"", "") : defaultAudienceType;
                String city = parts.length > 3 ? parts[3].trim().replace("\"", "") : null;
                String phone = parts.length > 4 ? parts[4].trim().replace("\"", "") : null;

                if (!EMAIL_REGEX.matcher(email).matches()) {
                    failed++;
                    if (errors.size() < 10) errors.add("Invalid email on row: " + email);
                    continue;
                }

                boolean exists = contactRepository.existsByEmailIgnoreCase(email);
                addOrUpdateContact(email, fullName, phone, city, audType, "CSV_IMPORT", true);

                if (exists) {
                    updated++;
                } else {
                    added++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error processing CSV: " + e.getMessage(), e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("addedCount", added);
        result.put("updatedCount", updated);
        result.put("failedCount", failed);
        result.put("errors", errors);
        return result;
    }

    @Transactional
    public boolean unsubscribeContactByToken(String token, String reason) {
        if (token == null || token.isBlank()) return false;
        Optional<EmailAudienceContact> opt = contactRepository.findByUnsubscribeToken(token.trim());
        if (opt.isPresent()) {
            EmailAudienceContact c = opt.get();
            c.setStatus("UNSUBSCRIBED");
            c.setUnsubscribedAt(LocalDateTime.now());
            c.setUnsubscribeReason(reason != null && !reason.isBlank() ? reason.trim() : "User requested opt-out");
            contactRepository.save(c);
            logger.info("Contact unsubscribed successfully: {}", c.getEmail());
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Page<EmailAudienceContact> getContacts(String audienceType, String status, String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        String cleanAud = (audienceType != null && !audienceType.isBlank() && !audienceType.equalsIgnoreCase("ALL")) ? audienceType.toUpperCase() : null;
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? "%" + search.trim().toLowerCase() + "%" : null;

        return contactRepository.searchContacts(cleanAud, cleanStatus, cleanSearch, pageable);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAudienceStats() {
        Map<String, Object> stats = new HashMap<>();
        long total = contactRepository.count();
        long subscribed = contactRepository.countByStatus("SUBSCRIBED");
        long unsubscribed = contactRepository.countByStatus("UNSUBSCRIBED");
        long bounced = contactRepository.countByStatus("BOUNCED");

        long passengers = contactRepository.countByAudienceType("PASSENGER");
        long drivers = contactRepository.countByAudienceType("DRIVER");
        long newsletters = contactRepository.countByAudienceType("NEWSLETTER");

        stats.put("totalContacts", total);
        stats.put("subscribedContacts", subscribed);
        stats.put("unsubscribedContacts", unsubscribed);
        stats.put("bouncedContacts", bounced);
        stats.put("passengerCount", passengers);
        stats.put("driverCount", drivers);
        stats.put("newsletterCount", newsletters);
        stats.put("optInRatePercent", total > 0 ? Math.round(((double) subscribed / total) * 1000.0) / 10.0 : 100.0);
        return stats;
    }

    // ==========================================
    // 3. CAMPAIGNS & BROADCASTS
    // ==========================================

    @Transactional(readOnly = true)
    public Page<EmailCampaign> getCampaigns(String status, String campaignType, String search, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanType = (campaignType != null && !campaignType.isBlank() && !campaignType.equalsIgnoreCase("ALL")) ? campaignType.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? "%" + search.trim().toLowerCase() + "%" : null;

        return campaignRepository.searchCampaigns(cleanStatus, cleanType, cleanSearch, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<EmailCampaign> getCampaign(Long id) {
        return campaignRepository.findById(id);
    }

    @Transactional
    public EmailCampaign saveCampaign(
            Long id,
            String name,
            String subject,
            String previewText,
            String campaignType,
            String targetAudience,
            String templateDataJson,
            String adminEmail
    ) {
        EmailCampaign campaign;
        if (id != null && id > 0) {
            campaign = campaignRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + id));
            if ("SENT".equalsIgnoreCase(campaign.getStatus()) || "SENDING".equalsIgnoreCase(campaign.getStatus())) {
                throw new IllegalStateException("Cannot modify a campaign that is already sent or sending.");
            }
        } else {
            campaign = EmailCampaign.builder()
                    .createdBy(adminEmail)
                    .status("DRAFT")
                    .recipientCount(0)
                    .sentCount(0)
                    .failedCount(0)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        campaign.setName(name);
        campaign.setSubject(subject);
        campaign.setPreviewText(previewText);
        campaign.setCampaignType(campaignType != null ? campaignType.toUpperCase() : "CUSTOM");
        campaign.setTargetAudience(targetAudience != null ? targetAudience.toUpperCase() : "ALL");
        campaign.setTemplateDataJson(templateDataJson);

        // Precompile sample HTML preview
        String compiled = compileBlocksToHtml(templateDataJson, Map.of(
                "firstName", "Commuter",
                "audienceType", targetAudience != null ? targetAudience : "Community",
                "previewText", previewText != null ? previewText : "",
                "unsubscribeUrl", "https://rideshare.com/unsubscribe?token=PREVIEW_TOKEN"
        ));
        campaign.setRenderedHtml(compiled);

        // Calculate estimated recipient count
        long eligibleRecipients = countEligibleRecipients(campaign.getTargetAudience());
        campaign.setRecipientCount((int) eligibleRecipients);

        return campaignRepository.save(campaign);
    }

    public long countEligibleRecipients(String audienceTarget) {
        if (audienceTarget == null || audienceTarget.equalsIgnoreCase("ALL")) {
            return contactRepository.countByStatus("SUBSCRIBED");
        } else {
            return contactRepository.countByAudienceTypeAndStatus(audienceTarget.toUpperCase(), "SUBSCRIBED");
        }
    }

    public Map<String, Object> sendDirectEmails(List<String> recipients, String subject, String previewText, String templateDataJson) {
        String cleanSubject = (subject != null && !subject.isBlank()) ? subject.trim() : "Your Journey Starts Together — Ride Together, Go Further";
        int sentCount = 0;
        int failedCount = 0;
        List<String> sentRecipients = new ArrayList<>();
        List<String> failedRecipients = new ArrayList<>();

        for (String rawRecipient : recipients) {
            String recipient = rawRecipient.trim().toLowerCase();
            if (recipient.isEmpty() || !recipient.contains("@")) continue;

            String compiled = compileBlocksToHtml(templateDataJson, Map.of(
                    "firstName", "Valued Commuter",
                    "audienceType", "Community",
                    "previewText", previewText != null ? previewText : "",
                    "unsubscribeUrl", "https://rideshare.com/unsubscribe?email=" + recipient
            ));

            boolean sent = emailService.sendHtmlEmail(
                    recipient,
                    cleanSubject,
                    compiled,
                    cleanSubject,
                    "CAMPAIGN_DIRECT"
            );

            if (sent) {
                sentCount++;
                sentRecipients.add(recipient);
            } else {
                failedCount++;
                failedRecipients.add(recipient);
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success", sentCount > 0);
        res.put("sentCount", sentCount);
        res.put("failedCount", failedCount);
        res.put("totalRecipients", recipients.size());
        res.put("recipients", sentRecipients);
        res.put("message", sentCount > 0
                ? "Real email dispatched to " + sentCount + " recipient" + (sentCount > 1 ? "s" : "") + " successfully."
                : "Failed to dispatch email to specified recipients.");
        return res;
    }

    public boolean sendTestEmail(String testRecipient, String subject, String previewText, String templateDataJson) {
        if (testRecipient == null || !testRecipient.contains("@")) {
            throw new IllegalArgumentException("Invalid destination email: " + testRecipient);
        }

        String cleanSubject = (subject != null && !subject.isBlank()) ? subject.trim() : "Your Journey Starts Together — Ride Together, Go Further";

        String compiled = compileBlocksToHtml(templateDataJson, Map.of(
                "firstName", "Valued Commuter",
                "audienceType", "Community",
                "previewText", previewText != null ? previewText : "",
                "unsubscribeUrl", "https://rideshare.com/unsubscribe?email=" + testRecipient.trim()
        ));

        return emailService.sendHtmlEmail(
                testRecipient.trim(),
                cleanSubject,
                compiled,
                cleanSubject,
                "CAMPAIGN_DIRECT"
        );
    }

    @Transactional
    public Map<String, Object> sendCampaign(Long campaignId) {
        EmailCampaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

        if ("SENT".equalsIgnoreCase(campaign.getStatus()) || "SENDING".equalsIgnoreCase(campaign.getStatus())) {
            throw new IllegalStateException("Campaign has already been sent.");
        }

        List<EmailAudienceContact> recipients;
        if ("ALL".equalsIgnoreCase(campaign.getTargetAudience())) {
            recipients = contactRepository.findByStatus("SUBSCRIBED");
        } else {
            recipients = contactRepository.findByAudienceTypeAndStatus(campaign.getTargetAudience().toUpperCase(), "SUBSCRIBED");
        }

        campaign.setStatus("SENDING");
        campaign.setRecipientCount(recipients.size());
        campaignRepository.save(campaign);

        int sent = 0;
        int failed = 0;

        for (EmailAudienceContact contact : recipients) {
            try {
                // Personalized token substitution
                String personalizedHtml = compileBlocksToHtml(campaign.getTemplateDataJson(), Map.of(
                        "firstName", contact.getFullName() != null && !contact.getFullName().isBlank() ? contact.getFullName().split(" ")[0] : "Commuter",
                        "audienceType", contact.getAudienceType(),
                        "previewText", campaign.getPreviewText() != null ? campaign.getPreviewText() : "",
                        "unsubscribeUrl", "https://rideshare.com/api/public/unsubscribe?token=" + contact.getUnsubscribeToken()
                ));

                boolean success = emailService.sendHtmlEmail(
                        contact.getEmail(),
                        campaign.getSubject(),
                        personalizedHtml,
                        campaign.getSubject(),
                        "CAMPAIGN_" + campaign.getCampaignType()
                );

                if (success) {
                    sent++;
                    contact.setLastEmailedAt(LocalDateTime.now());
                    contact.setTotalEmailsReceived(contact.getTotalEmailsReceived() + 1);
                    contactRepository.save(contact);
                } else {
                    failed++;
                }
            } catch (Exception e) {
                logger.error("Error dispatching campaign to {}: {}", contact.getEmail(), e.getMessage());
                failed++;
            }
        }

        campaign.setStatus("SENT");
        campaign.setSentCount(sent);
        campaign.setFailedCount(failed);
        campaign.setSentAt(LocalDateTime.now());
        campaignRepository.save(campaign);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("campaignId", campaign.getId());
        result.put("totalRecipients", recipients.size());
        result.put("sentCount", sent);
        result.put("failedCount", failed);
        return result;
    }

    // ==========================================
    // 4. AUTOMATED WORKFLOWS
    // ==========================================

    @Transactional(readOnly = true)
    public List<EmailWorkflow> getAllWorkflows() {
        return workflowRepository.findAll();
    }

    @Transactional
    public EmailWorkflow toggleWorkflow(Long id, boolean enabled) {
        EmailWorkflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + id));
        workflow.setEnabled(enabled);
        return workflowRepository.save(workflow);
    }

    @Transactional
    public EmailWorkflow updateWorkflow(
            Long id,
            String name,
            String subject,
            String previewText,
            String templateDataJson,
            Integer delayHours,
            Boolean enabled
    ) {
        EmailWorkflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + id));

        if (name != null) workflow.setName(name);
        if (subject != null) workflow.setSubject(subject);
        if (previewText != null) workflow.setPreviewText(previewText);
        if (templateDataJson != null) {
            workflow.setTemplateDataJson(templateDataJson);
            workflow.setRenderedHtml(compileBlocksToHtml(templateDataJson, Map.of(
                    "firstName", "New Member",
                    "audienceType", workflow.getTargetAudience(),
                    "unsubscribeUrl", "https://rideshare.com/unsubscribe?token=PREVIEW"
            )));
        }
        if (delayHours != null) workflow.setDelayHours(delayHours);
        if (enabled != null) workflow.setEnabled(enabled);

        return workflowRepository.save(workflow);
    }

    public void triggerWorkflowAsync(String triggerType, EmailAudienceContact contact) {
        new Thread(() -> {
            try {
                executeWorkflowForContact(triggerType, contact);
            } catch (Exception e) {
                logger.warn("Workflow trigger execution error for {} on {}: {}", triggerType, contact.getEmail(), e.getMessage());
            }
        }).start();
    }

    @Transactional
    public void executeWorkflowForContact(String triggerType, EmailAudienceContact contact) {
        if (!"SUBSCRIBED".equalsIgnoreCase(contact.getStatus())) {
            return;
        }

        Optional<EmailWorkflow> opt = workflowRepository.findFirstByTriggerTypeAndEnabledTrue(triggerType);
        if (opt.isEmpty()) {
            return;
        }

        EmailWorkflow workflow = opt.get();

        // 1. Target audience check
        if (!"ALL".equalsIgnoreCase(workflow.getTargetAudience())
                && !workflow.getTargetAudience().equalsIgnoreCase(contact.getAudienceType())) {
            return;
        }

        // 2. Strict De-duplication check: Have we already sent this workflow email to this contact?
        if (workflowExecutionRepository.existsByWorkflowIdAndContactEmail(workflow.getId(), contact.getEmail())) {
            logger.info("Skipping workflow '{}' for {} (already executed)", workflow.getName(), contact.getEmail());
            return;
        }

        workflow.setTotalTriggered(workflow.getTotalTriggered() + 1);

        String personalizedHtml = compileBlocksToHtml(workflow.getTemplateDataJson(), Map.of(
                "firstName", contact.getFullName() != null && !contact.getFullName().isBlank() ? contact.getFullName().split(" ")[0] : "Commuter",
                "audienceType", contact.getAudienceType(),
                "previewText", workflow.getPreviewText() != null ? workflow.getPreviewText() : "",
                "unsubscribeUrl", "https://rideshare.com/api/public/unsubscribe?token=" + contact.getUnsubscribeToken()
        ));

        boolean sent = emailService.sendHtmlEmail(
                contact.getEmail(),
                workflow.getSubject(),
                personalizedHtml,
                workflow.getSubject(),
                "WORKFLOW_" + workflow.getTriggerType()
        );

        if (sent) {
            workflow.setTotalSent(workflow.getTotalSent() + 1);
            contact.setLastEmailedAt(LocalDateTime.now());
            contact.setTotalEmailsReceived(contact.getTotalEmailsReceived() + 1);
            contactRepository.save(contact);

            EmailWorkflowExecution execution = EmailWorkflowExecution.builder()
                    .workflowId(workflow.getId())
                    .contactEmail(contact.getEmail())
                    .status("SENT")
                    .executedAt(LocalDateTime.now())
                    .build();
            workflowExecutionRepository.save(execution);
        } else {
            EmailWorkflowExecution execution = EmailWorkflowExecution.builder()
                    .workflowId(workflow.getId())
                    .contactEmail(contact.getEmail())
                    .status("FAILED")
                    .errorMessage("Email provider dispatch failed")
                    .executedAt(LocalDateTime.now())
                    .build();
            workflowExecutionRepository.save(execution);
        }

        workflowRepository.save(workflow);
    }

    // ==========================================
    // 5. ENGAGEMENT ANALYTICS
    // ==========================================

    public Map<String, Object> getAnalyticsDashboard() {
        Map<String, Object> result = new HashMap<>();

        long totalContacts = contactRepository.count();
        long subscribedContacts = contactRepository.countByStatus("SUBSCRIBED");
        long unsubscribedContacts = contactRepository.countByStatus("UNSUBSCRIBED");

        long totalEmailLogs = emailLogRepository.count();
        long successfulLogs = emailLogRepository.countByStatus("SENT");
        long failedLogs = emailLogRepository.countByStatus("FAILED") + emailLogRepository.countByStatus("BREVO_FAILED");

        double deliveryRate = totalEmailLogs > 0 ? ((double) successfulLogs / totalEmailLogs) * 100.0 : 100.0;
        double unsubscribeRate = totalContacts > 0 ? ((double) unsubscribedContacts / totalContacts) * 100.0 : 0.0;

        result.put("totalContacts", totalContacts);
        result.put("subscribedContacts", subscribedContacts);
        result.put("unsubscribedContacts", unsubscribedContacts);
        result.put("totalEmailsDispatched", totalEmailLogs);
        result.put("deliveredCount", successfulLogs);
        result.put("failedCount", failedLogs);
        result.put("deliveryRatePercent", Math.round(deliveryRate * 10.0) / 10.0);
        result.put("unsubscribeRatePercent", Math.round(unsubscribeRate * 10.0) / 10.0);

        // Recent campaign performance
        List<EmailCampaign> recentCampaigns = campaignRepository.findAll(
                PageRequest.of(0, 5, Sort.by("createdAt").descending())
        ).getContent();
        result.put("recentCampaigns", recentCampaigns);

        // Transparent provider capabilities notice
        Map<String, Object> providerNotice = new HashMap<>();
        providerNotice.put("openClickTrackingEnabled", false);
        providerNotice.put("explanation",
                "Transparent Tracking Notice: Accurate open and click-through metrics require inbound webhook configuration from your email provider (e.g. Brevo Events API or Mailgun Webhooks) and can be skewed by automated inbox privacy protections. RideShare only reports verified delivery receipts and opt-out rates to avoid misleading telemetry.");
        result.put("trackingDisclaimer", providerNotice);

        return result;
    }

    // ==========================================
    // SEED INITIAL SAMPLES
    // ==========================================

    private void initSampleWorkflows() {
        if (workflowRepository.count() == 0) {
            // 1. Waitlist Signup Welcome
            List<Map<String, Object>> welcomeBlocks = List.of(
                    Map.of("type", "header", "logoText", "RideShare", "tagline", "Ride Together, Go Further", "logoBadge", "OFFICIAL"),
                    Map.of("type", "hero", "badge", "REGISTRATION CONFIRMED", "title", "Welcome to the RideShare Waitlist, {{firstName}}!", "subtitle", "We are setting up verified carpool networks across key commute corridors. Your early spot is confirmed."),
                    Map.of("type", "button", "label", "Explore RideShare", "url", "https://rideshare.com", "alignment", "center"),
                    Map.of("type", "highlights", "title", "What Happens Next", "items", List.of(
                            Map.of("title", "1. Route Mapping", "desc", "We cluster commuters living in the same neighborhood heading to tech hubs."),
                            Map.of("title", "2. Pilot Invitation", "desc", "You will receive an invite to participate in early trial rides."),
                            Map.of("title", "3. Inaugural Credits", "desc", "Enjoy zero platform fees during our initial launch period.")
                    )),
                    Map.of("type", "trust_badge", "badgeText", "100% Verified Community", "subtext", "Zero spam. Genuine daily commuters split fair fuel costs."),
                    Map.of("type", "footer", "companyAddress", "RideShare Technologies", "unsubscribeUrl", "{{unsubscribeUrl}}")
            );

            try {
                String welcomeJson = objectMapper.writeValueAsString(welcomeBlocks);
                EmailWorkflow w1 = EmailWorkflow.builder()
                        .name("Waitlist Welcome Notification")
                        .triggerType("WAITLIST_SIGNUP")
                        .targetAudience("ALL")
                        .subject("Welcome to RideShare: Your Waitlist Confirmation")
                        .previewText("Your waitlist spot is confirmed. Here is what to expect next.")
                        .templateDataJson(welcomeJson)
                        .renderedHtml(compileBlocksToHtml(welcomeJson, Map.of("firstName", "Commuter", "unsubscribeUrl", "{{unsubscribeUrl}}")))
                        .delayHours(0)
                        .enabled(true)
                        .totalTriggered(0)
                        .totalSent(0)
                        .createdAt(LocalDateTime.now())
                        .build();
                workflowRepository.save(w1);

                // 2. Driver Onboarding Interest
                List<Map<String, Object>> driverBlocks = List.of(
                        Map.of("type", "header", "logoText", "RideShare", "tagline", "Ride Together, Go Further", "logoBadge", "DRIVER PARTNER"),
                        Map.of("type", "hero", "badge", "DRIVER ONBOARDING", "title", "Turn Your Daily Drive Into Reliable Fuel Savings", "subtitle", "Hi {{firstName}}, thank you for expressing interest in driving with RideShare. Here is how simple it is to get started."),
                        Map.of("type", "button", "label", "Complete Driver Application", "url", "https://rideshare.com/become-driver", "alignment", "center"),
                        Map.of("type", "highlights", "title", "Driver Partner Benefits", "items", List.of(
                                Map.of("title", "Cover 100% Fuel Costs", "desc", "Split expenses directly with verified coworkers on your route."),
                                Map.of("title", "You Control Who Rides", "desc", "Review passenger profiles and employer verification before accepting."),
                                Map.of("title", "Instant Bank Settlements", "desc", "Automated UPI and bank account payouts directly after trip completion.")
                        )),
                        Map.of("type", "trust_badge", "badgeText", "Zero Hidden Commissions", "subtext", "Transparent fixed platform fee with no predatory cuts."),
                        Map.of("type", "footer", "companyAddress", "RideShare Technologies", "unsubscribeUrl", "{{unsubscribeUrl}}")
                );

                String driverJson = objectMapper.writeValueAsString(driverBlocks);
                EmailWorkflow w2 = EmailWorkflow.builder()
                        .name("Driver Onboarding Welcome")
                        .triggerType("DRIVER_INTEREST")
                        .targetAudience("DRIVER")
                        .subject("Drive With RideShare: Simple Steps to Get Started")
                        .previewText("How to offset your commute fuel expenses with verified passengers.")
                        .templateDataJson(driverJson)
                        .renderedHtml(compileBlocksToHtml(driverJson, Map.of("firstName", "Driver", "unsubscribeUrl", "{{unsubscribeUrl}}")))
                        .delayHours(0)
                        .enabled(true)
                        .totalTriggered(0)
                        .totalSent(0)
                        .createdAt(LocalDateTime.now())
                        .build();
                workflowRepository.save(w2);

            } catch (Exception ignored) {}
        }
    }

    private void initSampleContactsIfEmpty() {
        if (contactRepository.count() == 0) {
            addOrUpdateContact("arjun.sharma@example.com", "Arjun Sharma", "+91 98111 22334", "Bengaluru", "PASSENGER", "WAITLIST_FORM", true);
            addOrUpdateContact("priya.patel@example.com", "Priya Patel", "+91 98222 33445", "Hyderabad", "PASSENGER", "WAITLIST_FORM", true);
            addOrUpdateContact("vikram.rao@example.com", "Vikram Rao", "+91 98333 44556", "Bengaluru", "DRIVER", "WAITLIST_FORM", true);
            addOrUpdateContact("neha.reddy@example.com", "Neha Reddy", "+91 98444 55667", "Pune", "DRIVER", "WAITLIST_FORM", true);
            addOrUpdateContact("rohit.verma@example.com", "Rohit Verma", "+91 98555 66778", "Delhi NCR", "PASSENGER", "CSV_IMPORT", true);
            addOrUpdateContact("ananya.iyer@example.com", "Ananya Iyer", "+91 98666 77889", "Chennai", "NEWSLETTER", "WEBSITE_SIGNUP", true);
        }
    }
}
