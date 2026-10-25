# RideShare Backend - Gmail SMTP Email Notification Service

This service provides transactional, responsive HTML and plain-text email delivery using Spring Boot's `spring-boot-starter-mail` connected to Gmail's secure SMTP server (`smtp.gmail.com:587` with STARTTLS).

---

## 1. Google Account Setup

To allow RideShare to dispatch emails via Gmail SMTP without a paid email service, you must use a **Google App Password**. Normal Gmail passwords are not supported by Google for SMTP access.

### Step 1: Enable 2-Step Verification
1. Open your [Google Account Security Settings](https://myaccount.google.com/security).
2. Under **"How you sign in to Google"**, select **2-Step Verification**.
3. Follow Google's prompts to enable 2-Step Verification via your mobile number or authenticator app.

### Step 2: Create a Google App Password
1. In the search bar at the top of your Google Account page, search for **"App passwords"** (or visit [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)).
2. Under **App name**, type `RideShare Backend`.
3. Click **Create**.
4. Google will generate a **16-character password** (formatted like `xxxx yyyy zzzz wwww`).
5. Copy this 16-character code (spaces can be omitted or included).

> ⚠️ **Important Security Rule**: Never commit this App Password to Git or share it with anyone.

---

## 2. Environment Variables Configuration

Create a local `.env` file or export environment variables in your deployment environment.

### Local Development (`.env` in `rideshare-backend/`)
```env
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=your-email@gmail.com
SMTP_APP_PASSWORD=your-16-char-google-app-password
MAIL_FROM=your-email@gmail.com
MAIL_FROM_NAME=RideShare
MAIL_ENABLED=true
```

In Windows PowerShell:
```powershell
$env:SMTP_USERNAME="your-email@gmail.com"
$env:SMTP_APP_PASSWORD="your-16-char-app-password"
$env:MAIL_FROM="your-email@gmail.com"
```

In Linux/macOS:
```bash
export SMTP_USERNAME="your-email@gmail.com"
export SMTP_APP_PASSWORD="your-16-char-app-password"
export MAIL_FROM="your-email@gmail.com"
```

### Production / Docker Deployment
In `docker-compose.yml` or container environment:
```yaml
environment:
  - SMTP_HOST=smtp.gmail.com
  - SMTP_PORT=587
  - SMTP_USERNAME=${SMTP_USERNAME}
  - SMTP_APP_PASSWORD=${SMTP_APP_PASSWORD}
  - MAIL_FROM=${SMTP_USERNAME}
  - MAIL_ENABLED=true
```

---

## 3. How to Run & Test Email Sending

### Run the Application
```bash
.\mvnw.cmd spring-boot:run
```

### Check Diagnostic Health (No Credentials Exposed)
You can verify SMTP configuration safely at any time:
```bash
curl http://localhost:8080/api/email/diagnostics
```
Expected output:
```json
{
  "host": "smtp.gmail.com",
  "port": 587,
  "configured": true,
  "senderConfigured": true,
  "fromAddress": "yo***@gmail.com",
  "fromName": "RideShare",
  "mailEnabled": true
}
```

### Test Direct Email Delivery
Send a test email to your inbox:
```bash
curl -X POST http://localhost:8080/api/email/test \
  -H "Content-Type: application/json" \
  -d '{"to": "your-personal-email@gmail.com"}'
```

### Automated Unit Tests
Run the mocked test suite (does not send real emails):
```bash
.\mvnw.cmd test -Dtest=EmailServiceTest
```

---

## 4. Triggering Real Business Notifications

All emails are sent **asynchronously** (`@Async`) so critical database transactions and booking speed are never blocked:

1. **User Signup**: Triggered at `POST /api/auth/register` &rarr; Sends branded OTP verification email.
2. **Password Reset**: Triggered at `POST /api/auth/forgot-password` &rarr; Sends password reset OTP.
3. **Password Updated**: Triggered at `POST /api/auth/reset-password` &rarr; Sends security confirmation email.
4. **Booking Confirmed**: Triggered on `POST /api/bookings` &rarr; Sends ride receipt to rider and new passenger alert to driver.
5. **Booking Cancelled**: Triggered on `PUT /api/bookings/{id}/cancel` &rarr; Sends cancellation confirmation.
6. **Trip Started**: Triggered on `PUT /api/rides/{id}/start` &rarr; Sends live tracking alert to passenger.
7. **Trip Completed**: Triggered on `PUT /api/rides/{id}/complete` &rarr; Sends completed receipt and rating reminder.
8. **Driver Application Review**: Triggered on `PUT /api/driver/applications/{id}/review` &rarr; Sends approval or revision details.

---

## 5. Troubleshooting & FAQs

### 1. `535-5.7.8 Username and Password not accepted`
- **Cause**: Using your personal Google account password instead of a Google App Password, or typo in App Password.
- **Fix**: Generate a fresh 16-character App Password at [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords). Ensure 2-Step Verification is ON.

### 2. `Could not connect to SMTP host: smtp.gmail.com, port: 587`
- **Cause**: Outbound port 587 blocked by your local firewall, VPN, or ISP.
- **Fix**: Disable VPN or allow outbound TCP traffic on port 587.

### 3. `STARTTLS is required`
- **Cause**: Plaintext connection attempted without TLS negotiation.
- **Fix**: The service automatically specifies `mail.smtp.starttls.enable=true` and `mail.smtp.starttls.required=true`.

### 4. Gmail Sending Limits
- Standard free personal Gmail accounts allow up to **500 emails per 24-hour period**.
- Google Workspace (paid) accounts allow up to **2,000 emails per 24 hours**.
- For large-scale production exceeding 500 emails/day, consider Amazon SES, Resend, or SendGrid by changing host and credentials in `application.properties`.

### 5. Fallback in Local Development
- If `SMTP_USERNAME` or `SMTP_APP_PASSWORD` is not configured, the service logs a developer notice in the server console and does not crash your application.
