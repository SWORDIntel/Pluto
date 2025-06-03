package org.signal.core.util.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowKeyStore
import org.robolectric.Shadows
import java.security.KeyStore
import java.security.cert.Certificate
import javax.crypto.SecretKey
import android.security.keystore.StrongBoxUnavailableException
import org.robolectric.shadows.ShadowLog
import org.junit.Assert.assertTrue
import org.mockito.Mockito.mock


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P]) // Target P for StrongBox, M for basic keystore
class AndroidKeyGuardianTest {

    private lateinit var keyStore: KeyStore

    @Before
    fun setUp() {
        // Configure Robolectric's ShadowLog to print to console to see logs from AndroidKeyGuardian
        ShadowLog.stream = System.out

        // Get the KeyStore instance
        keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null) // Initialize
        // Ensure the key from previous tests is cleared
        if (keyStore.containsAlias(AndroidKeyGuardian.KEY_ALIAS)) {
            keyStore.deleteEntry(AndroidKeyGuardian.KEY_ALIAS)
        }
    }

    @After
    fun tearDown() {
        // Clean up the key from the keystore after each test
        if (keyStore.containsAlias(AndroidKeyGuardian.KEY_ALIAS)) {
            keyStore.deleteEntry(AndroidKeyGuardian.KEY_ALIAS)
        }
    }

    @Test
    fun getOrCreateEncryptionKey_keyDoesNotExist_createsNewKey() {
        val secretKey = AndroidKeyGuardian.getOrCreateEncryptionKey()
        assertNotNull("SecretKey should not be null", secretKey)
        assertEquals("Key algorithm should be AES", KeyProperties.KEY_ALGORITHM_AES, secretKey.algorithm)

        // Verify key properties from KeyStore
        val entry = keyStore.getEntry(AndroidKeyGuardian.KEY_ALIAS, null)
        assertNotNull("KeyStore entry should not be null", entry)
        assertTrue("Entry should be SecretKeyEntry", entry is KeyStore.SecretKeyEntry)
        val retrievedKey = (entry as KeyStore.SecretKeyEntry).secretKey
        assertEquals("Retrieved key should match generated key", secretKey, retrievedKey)
    }

    @Test
    fun getOrCreateEncryptionKey_keyExists_retrievesExistingKey() {
        // First, create the key
        val initialKey = AndroidKeyGuardian.getOrCreateEncryptionKey()
        assertNotNull(initialKey)

        // Now, try to get it again
        val retrievedKey = AndroidKeyGuardian.getOrCreateEncryptionKey()
        assertNotNull(retrievedKey)
        assertEquals("Retrieved key should be the same as the initial key", initialKey, retrievedKey)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P]) // Test StrongBox specifically on P+
    fun getOrCreateEncryptionKey_strongBoxAttempted() {
        // This test mainly checks if setIsStrongBoxBacked(true) is called without crashing.
        // Robolectric's default KeyStore might not fully simulate StrongBox behavior,
        // but we can check if the call is made and doesn't throw an unexpected exception
        // other than StrongBoxUnavailableException (which is caught and logged by the impl).
        try {
            AndroidKeyGuardian.getOrCreateEncryptionKey()
            // If StrongBox were truly available and working in Robolectric, the key would be backed by it.
            // We are mostly testing that the API call path is exercised.
            // ShadowKeyGenParameterSpecBuilder might offer more introspection if needed.
        } catch (e: StrongBoxUnavailableException) {
            // This is acceptable if the testing environment doesn't simulate StrongBox
            System.out.println("StrongBoxUnavailableException caught in test, which is acceptable for Robolectric.")
        } catch (e: Exception) {
            fail("Should not throw other exceptions when attempting StrongBox: ${e.message}")
        }
        assertTrue("Key should be present in keystore", keyStore.containsAlias(AndroidKeyGuardian.KEY_ALIAS))
    }

    @Test(expected = KeyRetrievalFailedException::class)
    @Config(sdk = [Build.VERSION_CODES.M])
    fun getOrCreateEncryptionKey_aliasExistsButNotSecretKey_throwsKeyRetrievalFailed() {
        // Get the ShadowKeyStore
        val shadowKeyStore = Shadows.shadowOf(keyStore)

        // Create a dummy certificate to put into a TrustedCertificateEntry
        // We use Mockito to create a simple mock Certificate object.
        // The actual content of the certificate doesn't matter for this test,
        // only its type when wrapped in a KeyStore.Entry.
        val dummyCertificate = mock(Certificate::class.java)
        val trustedCertificateEntry = KeyStore.TrustedCertificateEntry(dummyCertificate)

        // Add this non-SecretKeyEntry to the keystore under the target alias
        shadowKeyStore.addEntry(AndroidKeyGuardian.KEY_ALIAS, trustedCertificateEntry)

        // Now, when AndroidKeyGuardian tries to get the key, it will find an entry,
        // but it won't be a SecretKeyEntry, leading to KeyRetrievalFailedException.
        AndroidKeyGuardian.getOrCreateEncryptionKey()
    }


    // UserAuthentication tests are difficult with Robolectric as it doesn't simulate the lock screen.
    // These would typically be manual or instrumentation tests.
    // @Test
    // fun getOrCreateEncryptionKey_userAuthenticationRequired() { ... }
}
