package com.eventplatform.signing;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class HmacSigner {

    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    public static final String TIMESTAMP_HEADER = "X-Webhook-Timestamp";
    public static final String WEBHOOK_ID_HEADER = "X-Webhook-Id";
    private static final String HMAC_SHA256 = "HmacSHA256";

    public String sign(String secret, String id, long timestamp, String rawBody) {
        try {
            String payloadToSign = id + "." + timestamp + "." + (rawBody != null ? rawBody : "");
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(payloadToSign.getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    public String buildSignatureHeader(String secret, String previousSecret, Instant previousSecretExpiresAt,
                                       String id, long timestamp, String rawBody) {
        String currentSig = sign(secret, id, timestamp, rawBody);

        if (previousSecret != null && previousSecretExpiresAt != null && previousSecretExpiresAt.isAfter(Instant.now())) {
            String prevSig = sign(previousSecret, id, timestamp, rawBody);
            return currentSig + " " + prevSig;
        }

        return currentSig;
    }
}
