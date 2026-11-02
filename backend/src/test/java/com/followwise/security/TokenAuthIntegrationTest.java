package com.followwise.security;

import com.followwise.AbstractIntegrationTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class TokenAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private TokenService tokenService;

    @Test
    void unauthenticatedRequestToProtectedEndpointReturns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/workspaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        String expiredToken = createCustomJwt(UUID.randomUUID(), "followwise", past, past.plus(Duration.ofMinutes(15)), jwtProperties.getSecret());

        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void malformedTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", "Bearer not.a.valid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void wrongSecretTokenReturns401() throws Exception {
        String wrongSecret = "wrong-secret-key-that-is-at-least-32-bytes-long!";
        Instant now = Instant.now();
        String invalidSignToken = createCustomJwt(UUID.randomUUID(), "followwise", now, now.plus(Duration.ofMinutes(15)), wrongSecret);

        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", "Bearer " + invalidSignToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void wrongIssuerTokenReturns401() throws Exception {
        Instant now = Instant.now();
        String wrongIssuerToken = createCustomJwt(UUID.randomUUID(), "invalid-issuer", now, now.plus(Duration.ofMinutes(15)), jwtProperties.getSecret());

        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", "Bearer " + wrongIssuerToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    private String createCustomJwt(UUID userId, String issuer, Instant issueTime, Instant expiryTime, String secret) throws Exception {
        MACSigner signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer(issuer)
                .issueTime(Date.from(issueTime))
                .expirationTime(Date.from(expiryTime))
                .build();
        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        signedJWT.sign(signer);
        return signedJWT.serialize();
    }
}
