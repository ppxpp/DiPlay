package com.shilapi.xcertplay.pioneer

/** RFC8439 AEAD on the old ARM head unit. No keys/payloads enter diagnostic logs. */
object PioneerNativeCrypto {
    // Map A/B/A on the verified old ARM firmware: lower decrypt latency and CPU than BC.
    // Platform Cipher/BC remain the default everywhere else; native availability includes AEAD tests.
    val enabled: Boolean get() = (PioneerDebugRuntime.nativeCryptoComparisonEnabled
        ?: (PioneerUsbPolicy.matchesDevice() || LocalConnectionExperiments.NATIVE_CHACHA)) && available

    val available: Boolean by lazy {
        if (!PioneerUsbPolicy.matchesDevice()) false else runCatching {
            System.loadLibrary("pioneer_crypto")
            fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val key = ByteArray(32) { (0x80 + it).toByte() }
            val nonce = hex("070000004041424344454647")
            val aad = hex("50515253c0c1c2c3c4c5c6c7")
            val plain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
            val expected = hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691")
            check(transform(true, key, nonce, plain, aad).contentEquals(expected))
            check(transform(false, key, nonce, expected, aad).contentEquals(plain))
            val corrupt = expected.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            check(runCatching { transform(false, key, nonce, corrupt, aad) }.exceptionOrNull() is javax.crypto.AEADBadTagException)
            true
        }.getOrDefault(false)
    }
    external fun transform(seal: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray): ByteArray
}
