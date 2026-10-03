package io.github.mkdevtests.umbra.nas

import com.hierynomus.security.SecurityProvider
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.security.jce.JceSecurityProvider

/**
 * The crypto of the SMB client. NTLM needs MD4, which Android's crypto lacks:
 * BouncyCastle (pure Java) provides it. [HybridSecurityProvider] asks the
 * system's crypto first (native, often hardware accelerated) and BouncyCastle
 * only for what the system lacks.
 */
object SmbSecurity {
    @Volatile var provider: () -> SecurityProvider = { BCSecurityProvider() }
}

class HybridSecurityProvider : SecurityProvider {
    private val system = JceSecurityProvider()
    private val bc = BCSecurityProvider()

    private fun <T> either(name: String, get: (SecurityProvider) -> T): T =
        if (name.equals("MD4", ignoreCase = true)) get(bc) else try {
            get(system)
        } catch (e: Exception) {
            get(bc)
        }

    override fun getDigest(name: String) = either(name) { it.getDigest(name) }
    override fun getMac(name: String) = either(name) { it.getMac(name) }
    override fun getCipher(name: String) = either(name) { it.getCipher(name) }
    override fun getAEADBlockCipher(name: String) = either(name) { it.getAEADBlockCipher(name) }
    override fun getDerivationFunction(name: String) = either(name) { it.getDerivationFunction(name) }
}
