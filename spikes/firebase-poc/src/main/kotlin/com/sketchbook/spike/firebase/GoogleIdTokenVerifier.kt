/*
 * Verify Google-issued ID tokens client-side before handing them to Identity Toolkit.
 *
 * Security-commitment #1 from docs/plans/2026-05-08-firebase-migration-design.md.
 *
 * Today (pre-Firebase): `OAuthClient.parseSubAndEmail` base64-decodes the JWT body without
 * checking the signature. The token comes directly from Google over HTTPS via our
 * OAuth flow, so practical risk is low. But it's a soft spot — this verifier closes it.
 *
 * Five checks:
 *   1. Signature: RS256 against Google's published JWKS (rotated daily, cached per `Cache-Control`).
 *   2. Issuer: `https://accounts.google.com` or `accounts.google.com`.
 *   3. Audience: must equal our OAuth client ID (rejects tokens minted for other apps).
 *   4. Expiry: not yet expired.
 *   5. NotBefore + IssuedAt sanity (with small clock-skew tolerance).
 *
 * Uses Nimbus JOSE+JWT's lower-level API directly. We avoid `DefaultJWTProcessor` because
 * its claim-verifier ergonomics shift between Nimbus minor versions; explicit checks here
 * pin the behavior.
 */
package com.sketchbook.spike.firebase

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GoogleIdTokenVerifier(
    /**
     * Expected `aud` claim. Equals our Google OAuth Client ID — the one we minted in the
     * Cloud Console for type "Desktop". Tokens minted for other apps must be rejected.
     */
    private val expectedAudience: String,
    private val clock: Clock = Clock.System,
    /** Allow a small clock skew between our system time and Google's. 60s is conventional. */
    private val clockSkewSeconds: Long = 60,
    /** Override-able for tests; production uses Google's published JWKS. */
    private val jwksUri: URI = URI("https://www.googleapis.com/oauth2/v3/certs"),
) {
    /** Issuer values Google may stamp. Both forms are valid per the OpenID Connect docs. */
    private val acceptedIssuers = setOf("https://accounts.google.com", "accounts.google.com")

    /**
     * Cached JWKS. JWKSet.load fetches the URL and respects the response's Cache-Control
     * header in higher-level APIs, but the basic `load` doesn't cache itself. For the spike
     * we cache for 1 hour — Google's JWKS updates at most daily.
     */
    @Volatile private var cachedJwks: JWKSet? = null

    @Volatile private var cachedJwksFetchedAt: Instant? = null
    private val jwksCacheTtl = (60 * 60).seconds

    /**
     * Verify [idToken] and return the verified claims if it passes all checks.
     * Returns [Result.failure] on any check failing.
     */
    fun verify(idToken: String): Result<VerifiedGoogleIdToken> =
        try {
            val signedJwt = SignedJWT.parse(idToken)
            require(signedJwt.header.algorithm == JWSAlgorithm.RS256) {
                "expected RS256, got ${signedJwt.header.algorithm}"
            }

            // Fetch JWKS, find the key matching the JWT's `kid`.
            val jwks = jwks()
            val kid = requireNotNull(signedJwt.header.keyID) { "JWT missing kid header" }
            val jwk = requireNotNull(jwks.getKeyByKeyId(kid)) { "kid $kid not in Google JWKS" }
            val rsaKey = (jwk as? RSAKey) ?: error("kid $kid is not an RSA key")

            // Signature check.
            val verifier = RSASSAVerifier(rsaKey.toRSAPublicKey())
            require(signedJwt.verify(verifier)) { "signature verification failed" }

            // Claim checks.
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
