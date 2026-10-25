package com.rideshare.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class EmailTemplateService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    private String escape(String input) {
        return input == null ? "" : HtmlUtils.htmlEscape(input);
    }

    private static final String DEFAULT_LOGO_URL = "https://cultural-giddiness-pueblo.ngrok-free.dev/images/logo.png";

    private String wrapInBaseTemplate(String title, String bodyContent) {
        return "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "  <meta charset=\"utf-8\">"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "  <title>" + escape(title) + "</title>"
                + "  <style>"
                + "    body { margin: 0; padding: 0; background-color: #04060A; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #FFFFFF; -webkit-font-smoothing: antialiased; }"
                + "    .container { max-width: 580px; margin: 30px auto; background-color: #0B101D; border-radius: 18px; overflow: hidden; border: 1px solid rgba(255, 255, 255, 0.08); box-shadow: 0 10px 40px rgba(0,0,0,0.6); }"
                + "    .header { background: linear-gradient(180deg, #070B14 0%, #0B101D 100%); padding: 26px 24px 20px; text-align: center; border-bottom: 1px solid rgba(0, 229, 255, 0.18); }"
                + "    .logo-badge { display: inline-block; background: rgba(0, 229, 255, 0.12); border: 1px solid rgba(0, 229, 255, 0.3); border-radius: 999px; padding: 4px 14px; margin-top: 10px; font-weight: 700; font-size: 11px; color: #00E5FF; letter-spacing: 1.2px; text-transform: uppercase; }"
                + "    .brand-name { font-size: 26px; font-weight: 800; color: #FFFFFF; letter-spacing: -0.5px; margin: 0; }"
                + "    .content { padding: 32px 28px; }"
                + "    .title { font-size: 22px; font-weight: 700; color: #FFFFFF; margin: 0 0 14px; line-height: 1.3; }"
                + "    .paragraph { font-size: 15px; line-height: 1.6; color: #A0AEC0; margin: 0 0 20px; }"
                + "    .card { background-color: #1C2230; border-radius: 14px; padding: 20px; margin: 24px 0; border: 1px solid rgba(255, 255, 255, 0.06); }"
                + "    .otp-box { background: rgba(0, 229, 255, 0.08); border: 2px dashed #00E5FF; border-radius: 16px; padding: 22px; text-align: center; margin: 28px 0; }"
                + "    .otp-code { font-size: 38px; font-weight: 800; letter-spacing: 10px; color: #00E5FF; margin: 0; font-family: monospace; }"
                + "    .otp-expiry { font-size: 13px; color: #8A99AD; margin-top: 10px; }"
                + "    .detail-row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid rgba(255,255,255,0.05); font-size: 14px; }"
                + "    .detail-label { color: #8A99AD; font-weight: 500; }"
                + "    .detail-value { color: #FFFFFF; font-weight: 600; text-align: right; }"
                + "    .footer { padding: 24px 28px; text-align: center; font-size: 12px; color: #64748B; border-top: 1px solid rgba(255, 255, 255, 0.05); background-color: #0E121B; }"
                + "    .footer a { color: #00E5FF; text-decoration: none; }"
                + "  </style>"
                + "</head>"
                + "<body>"
                + "  <div class=\"container\">"
                + "    <div class=\"header\">"
                + "      <div style=\"display: inline-flex; align-items: center; justify-content: center;\">"
                + "        <img src=\"" + DEFAULT_LOGO_URL + "\" width=\"38\" height=\"38\" alt=\"RideShare\" style=\"display: inline-block; vertical-align: middle; border-radius: 9px; margin-right: 10px; border: 1px solid rgba(0,229,255,0.3);\" />"
                + "        <span class=\"brand-name\" style=\"vertical-align: middle;\">Ride<span style=\"color: #00E5FF;\">Share</span></span>"
                + "      </div><br/>"
                + "      <div class=\"logo-badge\">Ride Together, Go Further</div>"
                + "    </div>"
                + "    <div class=\"content\">"
                + bodyContent
                + "    </div>"
                + "    <div class=\"footer\">"
                + "      <p>This is an automated notification from RideShare. Please do not reply directly to this email.</p>"
                + "      <p>&copy; " + LocalDateTime.now().getYear() + " RideShare Inc. Safe, smart and shared city rides.</p>"
                + "    </div>"
                + "  </div>"
                + "</body>"
                + "</html>";
    }

    public String buildOtpTemplate(String otp, String purpose, int expiryMinutes) {
        String purposeTitle;
        String purposeDesc;

        if ("SIGNUP".equalsIgnoreCase(purpose)) {
            purposeTitle = "Verify Your Email Address";
            purposeDesc = "Welcome to RideShare! Please use the 6-digit verification code below to verify your email and activate your account.";
        } else if ("LOGIN".equalsIgnoreCase(purpose)) {
            purposeTitle = "Your RideShare Login Code";
            purposeDesc = "You requested a single-use verification code to log in to your RideShare account. Enter the 6-digit code below to sign in:";
        } else {
            purposeTitle = "Reset Your Password";
            purposeDesc = "We received a request to reset the password for your RideShare account. Enter the verification code below to continue:";
        }

        String body = "<h2 class=\"title\">" + escape(purposeTitle) + "</h2>"
                + "<p class=\"paragraph\">" + escape(purposeDesc) + "</p>"
                + "<div class=\"otp-box\">"
                + "  <div class=\"otp-code\">" + escape(otp) + "</div>"
                + "  <div class=\"otp-expiry\">Expires in " + expiryMinutes + " minutes &bull; Single-use code</div>"
                + "</div>"
                + "<div class=\"card\" style=\"background: rgba(255, 82, 82, 0.08); border: 1px solid rgba(255, 82, 82, 0.2);\">"
                + "  <p class=\"paragraph\" style=\"font-size: 13px; color: #FFA8A8; margin: 0;\">"
                + "    <strong>Important Security Advice:</strong> Never share this OTP code with anyone, including RideShare drivers, representatives, or customer support. RideShare will never ask for your OTP over phone, chat, or email."
                + "  </p>"
                + "</div>"
                + "<p class=\"paragraph\" style=\"font-size: 12px; color: #8A99AD; margin-top: 16px;\">"
                + "  If you did not request this code, your account remains secure and no action is required."
                + "</p>";

        return wrapInBaseTemplate(purposeTitle, body);
    }

    public String buildPasswordResetSuccessTemplate(String name) {
        String safeName = escape(name != null && !name.isBlank() ? name : "User");
        String body = "<h2 class=\"title\">Password Successfully Updated</h2>"
                + "<p class=\"paragraph\">Hello " + safeName + ",</p>"
                + "<p class=\"paragraph\">The password for your RideShare account has been successfully changed.</p>"
                + "<div class=\"card\">"
                + "  <p class=\"paragraph\" style=\"margin:0; font-size:14px; color:#00E676;\">&#10003; Your account is secure and ready for use.</p>"
                + "</div>"
                + "<p class=\"paragraph\" style=\"font-size: 13px; color: #8A99AD;\">"
                + "  If you did not perform this change, please contact RideShare customer support immediately."
                + "</p>";

        return wrapInBaseTemplate("Password Reset Confirmation", body);
    }

    public String buildBookingConfirmedTemplate(String passengerName, Long bookingId, String pickup, String dropoff, Double fare, String driverName, LocalDateTime departureTime) {
        String formattedTime = departureTime != null ? departureTime.format(TIME_FORMATTER) : "Scheduled Soon";
        String body = "<h2 class=\"title\">Booking Confirmed! &#127881;</h2>"
                + "<p class=\"paragraph\">Hello " + escape(passengerName) + ", your ride seat has been successfully reserved and confirmed.</p>"
                + "<div class=\"card\">"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Booking ID</span><span class=\"detail-value\">#" + bookingId + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Driver</span><span class=\"detail-value\">" + escape(driverName) + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Pickup Location</span><span class=\"detail-value\">" + escape(pickup) + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Dropoff Destination</span><span class=\"detail-value\">" + escape(dropoff) + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Departure Time</span><span class=\"detail-value\">" + escape(formattedTime) + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Fare</span><span class=\"detail-value\" style=\"color:#00E5FF;\">&#8377;" + String.format("%.2f", fare != null ? fare : 0.0) + "</span></div>"
                + "</div>"
                + "<p class=\"paragraph\" style=\"font-size: 13px; color: #8A99AD;\">"
                + "  Please arrive at the pickup spot 5 minutes prior to departure."
                + "</p>";

        return wrapInBaseTemplate("Booking Confirmed - #" + bookingId, body);
    }

    public String buildNewPassengerAlertDriverTemplate(String driverName, Long bookingId, String passengerName, String pickup, String dropoff, Double fare) {
        String body = "<h2 class=\"title\">New Passenger Joined Your Ride! &#128663;</h2>"
                + "<p class=\"paragraph\">Hello " + escape(driverName) + ", a new passenger has confirmed a seat on your published ride.</p>"
                + "<div class=\"card\">"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Booking ID</span><span class=\"detail-value\">#" + bookingId + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Passenger Name</span><span class=\"detail-value\">" + escape(passengerName) + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Pickup Point</span><span class=\"detail-value\">" + escape(pickup) + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Dropoff Point</span><span class=\"detail-value\">" + escape(dropoff) + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Fare</span><span class=\"detail-value\" style=\"color:#00E5FF;\">&#8377;" + String.format("%.2f", fare != null ? fare : 0.0) + "</span></div>"
                + "</div>";

        return wrapInBaseTemplate("New Passenger Booked - #" + bookingId, body);
    }

    public String buildBookingCancelledTemplate(String name, Long bookingId, String reason) {
        String body = "<h2 class=\"title\" style=\"color:#FF5252;\">Booking Cancelled</h2>"
                + "<p class=\"paragraph\">Hello " + escape(name) + ",</p>"
                + "<p class=\"paragraph\">Booking #" + bookingId + " has been cancelled.</p>"
                + "<div class=\"card\">"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Booking ID</span><span class=\"detail-value\">#" + bookingId + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Cancellation Details</span><span class=\"detail-value\">" + escape(reason) + "</span></div>"
                + "</div>"
                + "<p class=\"paragraph\" style=\"font-size: 13px; color: #8A99AD;\">"
                + "  Any applicable fare refunds will be processed back to your original payment method / wallet."
                + "</p>";

        return wrapInBaseTemplate("Booking Cancelled - #" + bookingId, body);
    }

    public String buildTripStartedTemplate(String passengerName, Long rideId, String driverName) {
        String body = "<h2 class=\"title\">Trip Started &#128661;</h2>"
                + "<p class=\"paragraph\">Hello " + escape(passengerName) + ", your ride #" + rideId + " with driver " + escape(driverName) + " has officially begun.</p>"
                + "<div class=\"card\">"
                + "  <p class=\"paragraph\" style=\"margin:0; font-size:14px; color:#00E5FF;\">Safe travels! You can track your trip in real time inside the RideShare app.</p>"
                + "</div>";

        return wrapInBaseTemplate("Your Trip Has Started - Ride #" + rideId, body);
    }

    public String buildTripCompletedTemplate(String passengerName, Long rideId, Double fare) {
        String body = "<h2 class=\"title\">Trip Completed Safely &#9989;</h2>"
                + "<p class=\"paragraph\">Hello " + escape(passengerName) + ", thank you for riding with RideShare! Your trip #" + rideId + " has finished.</p>"
                + "<div class=\"card\">"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Ride ID</span><span class=\"detail-value\">#" + rideId + "</span></div>"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Total Fare</span><span class=\"detail-value\" style=\"color:#00E5FF;\">&#8377;" + String.format("%.2f", fare != null ? fare : 0.0) + "</span></div>"
                + "</div>"
                + "<p class=\"paragraph\" style=\"font-size: 13px; color: #8A99AD;\">"
                + "  We hope you had a pleasant journey! Don't forget to rate your driver in the app."
                + "</p>";

        return wrapInBaseTemplate("Trip Completed - Ride #" + rideId, body);
    }

    public String buildDriverApplicationReviewTemplate(String applicantName, String status, String feedback) {
        boolean isApproved = "APPROVED".equalsIgnoreCase(status);
        String title = isApproved ? "Driver Application Approved! &#127881;" : "Driver Application Update";
        String statusColor = isApproved ? "#00E676" : "#FFB300";

        String body = "<h2 class=\"title\">" + escape(title) + "</h2>"
                + "<p class=\"paragraph\">Hello " + escape(applicantName) + ",</p>"
                + "<p class=\"paragraph\">Your application to become a verified RideShare driver has been reviewed:</p>"
                + "<div class=\"card\">"
                + "  <div class=\"detail-row\"><span class=\"detail-label\">Application Status</span><span class=\"detail-value\" style=\"color:" + statusColor + ";\">" + escape(status) + "</span></div>"
                + (feedback != null && !feedback.isBlank() ? "<div class=\"detail-row\"><span class=\"detail-label\">Review Feedback</span><span class=\"detail-value\">" + escape(feedback) + "</span></div>" : "")
                + "</div>"
                + (isApproved
                    ? "<p class=\"paragraph\" style=\"color:#00E5FF;\">You can now switch to Driver Mode in the RideShare app and publish rides!</p>"
                    : "<p class=\"paragraph\" style=\"font-size: 13px; color: #8A99AD;\">Please review the feedback above or update your documents in the app to proceed.</p>");

        return wrapInBaseTemplate(title, body);
    }
}
