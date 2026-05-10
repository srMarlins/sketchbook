package com.sketchbook.auth.firebase

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Verify Google-issued ID tokens client-side before handing them to Identity Toolkit.
 * Security-commitment #1 in `docs/plans/2026-05-08-firebase-migration-design.md`.
 *
 * Five checks: RS256 signature (against Google's published JWKS), issuer, audience (must
 * equal our OAuth client ID — rejects tokens minted for other apps), expiry, and signed-JWT
 * sanity. Clock skew tolerated by [clockSkewSeconds].
 *
 * Uses Nimbus JOSE+JWT's lower-level API directly. We avoid `DefaultJWTProcessor` because
 * its claim-verifier ergonomics shift between Nimbus minor versions; explicit checks here
 * pin the behavior.
 */
class GoogleIdTokenVerifier(
    /**
     * Expected `aud` claim. Equals our Google OAuth Client ID (Desktop type). Tokens minted
     * for other apps must be rejected.
     */
    private val expectedAudience: String,
    private val clock: Clock = Clock.System,
    private val clockSkewSeconds: Long = 60,
    private val jwksUri: URI = URI("https://www.googleapis.com/oauth2/v3/certs"),
) {
    private val acceptedIssuers = setOf("https://accounts.google.com", "accounts.google.com")

    @Volatile private var cachedJwks: JWKSet? = null

    @Volatile private var cachedJwksFetchedAt: Instant? = null
    private val jwksCacheTtl = (60 * 60).seconds

    fun verify(idToken: String): Result<VerifiedGoogleIdToken> =
        try {
            val signedJwt = SignedJWT.parse(idToken)
            require(signedJwt.header.algorithm == JWSAlgorithm.RS256) {
                "expected RS256, got ${signedJwt.header.algorithm}"
            }

            val jwks = jwks()
            val kid = requireNotNull(signedJwt.header.keyID) { "JWT missing kid header" }
            val jwk = requireNotNull(jwks.getKeyByKeyId(kid)) { "kid $kid not in Google JWKS" }
            val rsaKey = (jwk as? RSAKey) ?: error("kid $kid is not an RSA key")

            val verifier = RSASSAVerifier(rsaKey.toRSAPublicKey())
            require(signedJwt.verify(verifier)) { "signature verification failed" }

            val claims = signedJwt.jwtClaimsSet
            val now = clock.now()
            val skew = clockSkewSeconds.seconds

            val issuer = claims.issuer ?: error("missing iss claim")
            require(issuer in acceptedIssuers) { "untrusted issuer: $issuer" }

            val audience = claims.audience.firstOrNull() ?: error("missing aud claim")
            require(audience == expectedAudience) {
                "audience mismatch: expected $expectedAudience, got $audience"
            }

            val exp = claims.expirationTime ?: error("missing exp claim")
            val expInstant = Instant.fromEpochMilliseconds(exp.time)
            require(expInstant + skew >= now) { "token expired at $expInstant (now=$now)" }

            val sub = claims.subject ?: error("missing sub claim")
            val email = claims.getStringClaim("email") ?: error("missing email claim")
            val emailVerified = claims.getBooleanClaim("email_verified") ?: false

            Result.success(
                VerifiedGoogleIdToken(
                    sub = sub,
                    email = email,
                    emailVerified = emailVerified,
                    issuer = issuer,
                    audience = audience,
                    expiresAt = expInstant,
                ),
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private fun jwks(): JWKSet {
        val now = clock.now()
        val cached = cachedJwks
        val fetchedAt = cachedJwksFetchedAt
        if (cached != null && fetchedAt != null && now < fetchedAt + jwksCacheTtl) {
            return cached
        }
        val fresh = JWKSet.load(jwksUri.toURL())
        cachedJwks = fresh
        cachedJwksFetchedAt = now
        return fresh
    }
}
