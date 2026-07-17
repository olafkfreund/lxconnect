package com.lxconnect.mcp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

class McpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // minSdk 26: notification channels always available
        val channel = NotificationChannel(
            CHANNEL_ID,
            "lxconnect Service Channel",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running the local MCP server background service"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "mcp_service_channel"

        private const val TAG = "McpApplication"
        const val TLS_KEY_ALIAS = "lxconnect"
        private const val TLS_KEYSTORE_FILE = "lxconnect_tls.p12"

        // Single source of truth for the pairing key: generated once with SecureRandom
        // on first run, overwritten by the pairing flow. Never ship a known default.
        fun getSharedKey(context: Context): String {
            val prefs = context.getSharedPreferences("lxconnect_prefs", Context.MODE_PRIVATE)
            prefs.getString("secure_shared_key", null)?.let { return it }
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            val key = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("secure_shared_key", key).apply()
            return key
        }

        private fun getTlsKeystorePassword(context: Context): CharArray {
            val prefs = context.getSharedPreferences("lxconnect_prefs", Context.MODE_PRIVATE)
            prefs.getString("tls_keystore_password", null)?.let { return it.toCharArray() }
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            val password = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("tls_keystore_password", password).apply()
            return password.toCharArray()
        }

        // Self-signed RSA cert, generated once on-device and reused across restarts.
        // BouncyCastle is used only for the X509v3 builder (no hand-rolled ASN.1); the
        // provider instance is passed directly (not registered via Security.addProvider)
        // to avoid clashing with Android's own crippled "BC" provider.
        private fun generateSelfSignedKeyStore(context: Context, password: CharArray): KeyStore {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = Date()
            val notAfter = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000) // 10 years
            val name = X500Name("CN=lxconnect")
            val certBuilder = JcaX509v3CertificateBuilder(
                name, BigInteger(64, SecureRandom()), now, notAfter, name, keyPair.public
            )
            val bc = BouncyCastleProvider()
            val signer = JcaContentSignerBuilder("SHA256WithRSA").setProvider(bc).build(keyPair.private)
            val cert: X509Certificate = JcaX509CertificateConverter().setProvider(bc)
                .getCertificate(certBuilder.build(signer))

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry(TLS_KEY_ALIAS, keyPair.private, password, arrayOf(cert))

            // Write via a temp file + rename so a killed process can't leave a corrupt keystore.
            val file = File(context.filesDir, TLS_KEYSTORE_FILE)
            val tmp = File(context.filesDir, "$TLS_KEYSTORE_FILE.tmp")
            tmp.outputStream().use { keyStore.store(it, password) }
            tmp.renameTo(file)
            return keyStore
        }

        fun getTlsKeyStore(context: Context): KeyStore {
            val password = getTlsKeystorePassword(context)
            val file = File(context.filesDir, TLS_KEYSTORE_FILE)
            if (file.exists()) {
                try {
                    return KeyStore.getInstance("PKCS12").apply {
                        file.inputStream().use { load(it, password) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load TLS keystore, regenerating", e)
                    file.delete()
                }
            }
            return generateSelfSignedKeyStore(context, password)
        }

        fun getTlsKeystorePasswordChars(context: Context): CharArray = getTlsKeystorePassword(context)

        // Lowercase hex SHA-256 of the DER-encoded X509 cert, no separators — used by the
        // Linux side to pin the self-signed cert during pairing.
        fun getCertFingerprint(context: Context): String {
            val cert = getTlsKeyStore(context).getCertificate(TLS_KEY_ALIAS) as X509Certificate
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
