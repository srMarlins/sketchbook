package com.sketchbook.auth

class FakeTokenStore(
    initial: String? = null,
) : TokenStore {
    @Volatile private var token: String? = initial
    val writes = mutableListOf<String>()
    val clears =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    override suspend fun read(): String? = token

    override suspend fun write(refreshToken: String) {
        token = refreshToken
        writes += refreshToken
    }

    override suspend fun clear() {
        token = null
        clears.incrementAndGet()
    }
}
