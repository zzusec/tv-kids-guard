package com.family.tvguard;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The embedded roots must stay valid public anchors and load into a usable trust store. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 24, 33})
public class UpdateTrustTest {

    @Test
    public void embeddedRootsAreSelfSignedCertificates() throws Exception {
        String pem = new String(UpdateTrust.pemBytes(), StandardCharsets.US_ASCII);
        assertEquals(2, pem.split("-----BEGIN CERTIFICATE-----", -1).length - 1);
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"));
        Collection<? extends Certificate> roots = UpdateTrust.embedded();
        assertEquals(2, roots.size());
        for (Certificate certificate : roots) {
            X509Certificate root = (X509Certificate) certificate;
            root.checkValidity();
            // A trust anchor is self-signed; a leaf or intermediate would not verify here.
            root.verify(root.getPublicKey());
            assertTrue(root.getBasicConstraints() >= 0);
        }
    }

    @Test
    public void storeAcceptsBothRootsAndKeepsPlatformEntries() throws Exception {
        KeyStore store = UpdateTrust.store();
        assertEquals(2, store.size());
        assertNotNull(store.getCertificate(store.aliases().nextElement()));
    }

    @Test
    public void conciseMapsStaleTrustStoreErrorsToAParentReadableMessage() {
        String message = UpdateManager.concise(
                new java.security.cert.CertPathValidatorException("Trust anchor for certification path not found."));
        assertEquals("证书校验失败：电视系统证书过旧，请稍后重试", message);
    }
}
