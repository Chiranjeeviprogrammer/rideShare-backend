# Brevo OTP delivery

The backend sends authentication codes through Brevo's Transactional Email API. Credentials are server-side only; do not add them to the React Native app, web clients, source code, or commits.

## Configure the backend

Set these environment variables on the backend host (or in an untracked `rideShare-backend/.env` loaded by the host):

```text
BREVO_API_KEY=your_brevo_transactional_api_key
BREVO_SENDER_EMAIL=verified-sender@example.com
BREVO_SENDER_NAME=RideShare
```

In Brevo, add and verify the sender address under **Senders, Domains & Dedicated IPs**, or authenticate the sending domain and publish the supplied DNS records. Brevo will reject mail from an unverified sender.

Restart the backend after changing environment variables. `BREVO_API_KEY` is read by `application.properties` as `brevo.api.key`; the API key is never returned by the application.

## Test safely

1. Start the backend with the three variables configured.
2. Register a new email address, or call `POST /api/auth/forgot-password` with an existing address.
3. Use the six-digit code received by email with `POST /api/auth/verify-otp` (or the purpose-specific endpoint).
4. Confirm that a second use of the same code fails, that a wrong code is rejected, and that resend is rejected for 60 seconds.

Codes expire after five minutes, are bcrypt-hashed at rest, are single-use, and are limited to five failed attempts. A code is stored only after its delivery is accepted; on a resend failure the previous active code remains valid. New successfully delivered codes invalidate earlier active codes for the same email and purpose.

## Troubleshooting

- `401` / `403` from Brevo: use a valid transactional API key and make sure the sender is verified.
- `400` from Brevo: check the sender, recipient formatting, and account status.
- timeouts: the backend uses a 6-second connect timeout and 10-second read timeout; check outbound HTTPS access to `api.brevo.com`.
- delivery accepted but missing: check Brevo Transactional logs, spam/junk, and domain SPF/DKIM configuration.

The application records a sanitized delivery outcome in its email log; it never logs OTP values or API keys.
