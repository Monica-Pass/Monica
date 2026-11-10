package takagi.ru.monica.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.biometric.BiometricManager;
import androidx.core.hardware.fingerprint.FingerprintManagerCompat;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowFingerprintManager;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * Calls the real AndroidX BiometricManager over controlled Android framework shadows.
 * This is availability compatibility coverage, not a prompt, physical sensor or key/TEE test.
 */
@SuppressWarnings("deprecation")
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE)
public final class BiometricLegacyFingerprintCompatibilityTest {
    private Context context;
    private Object frameworkFingerprint;
    private ShadowFingerprintManager fingerprint;
    private ShadowPackageManager packages;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        packages = Shadow.extract(context.getPackageManager());
        packages.setSystemFeature(PackageManager.FEATURE_FINGERPRINT, true);
        // Android 26/28 Context.FINGERPRINT_SERVICE equals "fingerprint"; SDK37 removed the constant.
        frameworkFingerprint = context.getSystemService("fingerprint");
        assertNotNull("Robolectric must expose its platform fingerprint service", frameworkFingerprint);
        fingerprint = Shadow.extract(frameworkFingerprint);
        fingerprint.setIsHardwareDetected(false);
        fingerprint.setDefaultFingerprints(0);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        assertNotNull("The simulated device must be securable", keyguard);
        ShadowKeyguardManager keyguardShadow = Shadow.extract(keyguard);
        keyguardShadow.setIsKeyguardSecure(true);
        keyguardShadow.setIsDeviceSecure(true);
    }

    @Test @Config(sdk = 26)
    public void api26EnrolledFingerprintRemainsAvailableAlthoughCoreCompatIsNoOp() throws Exception {
        setFingerprint(true, 1);
        assertDeprecatedCoreNoOpAgainstEnrolledPlatform();
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS, strongAvailability());
    }

    @Test @Config(sdk = 26)
    public void api26HardwareWithoutEnrollmentRemainsUnavailable() throws Exception {
        setFingerprint(true, 0);
        assertTrue(platformBoolean("isHardwareDetected"));
        assertFalse(platformBoolean("hasEnrolledFingerprints"));
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, strongAvailability());
    }

    @Test @Config(sdk = 26)
    public void api26WithoutFingerprintHardwareRemainsUnavailable() throws Exception {
        setFingerprint(false, 0);
        packages.setSystemFeature(PackageManager.FEATURE_FINGERPRINT, false);
        assertFalse(platformBoolean("isHardwareDetected"));
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, strongAvailability());
    }

    @Test @Config(sdk = 28)
    public void api28EnrolledFingerprintRemainsAvailableAlthoughCoreCompatIsNoOp() throws Exception {
        setFingerprint(true, 1);
        assertDeprecatedCoreNoOpAgainstEnrolledPlatform();
        assertEquals(BiometricManager.BIOMETRIC_SUCCESS, strongAvailability());
    }

    @Test @Config(sdk = 28)
    public void api28SecuredDeviceWithoutFingerprintEnrollmentRemainsUnknown() throws Exception {
        setFingerprint(true, 0);
        assertTrue(platformBoolean("isHardwareDetected"));
        assertFalse(platformBoolean("hasEnrolledFingerprints"));
        // API28 can have non-fingerprint sensors whose strength/enrollment cannot be queried.
        // AndroidX returns UNKNOWN for this secured-device case. It is deliberately not SUCCESS.
        assertEquals(BiometricManager.BIOMETRIC_STATUS_UNKNOWN, strongAvailability());
    }

    @Test @Config(sdk = 28)
    public void api28WithoutFingerprintHardwareRemainsUnavailable() throws Exception {
        setFingerprint(false, 0);
        packages.setSystemFeature(PackageManager.FEATURE_FINGERPRINT, false);
        assertFalse(platformBoolean("isHardwareDetected"));
        assertEquals(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, strongAvailability());
    }

    private void setFingerprint(boolean hardware, int enrolled) {
        fingerprint.setIsHardwareDetected(hardware);
        fingerprint.setDefaultFingerprints(enrolled);
    }

    private void assertDeprecatedCoreNoOpAgainstEnrolledPlatform() throws Exception {
        assertTrue("The platform shadow must actually report hardware", platformBoolean("isHardwareDetected"));
        assertTrue("The platform shadow must actually report enrollment", platformBoolean("hasEnrolledFingerprints"));
        FingerprintManagerCompat oldCore = FingerprintManagerCompat.from(context);
        assertFalse("The selected Core legacy method is the no-op regression control", oldCore.isHardwareDetected());
        assertFalse("The selected Core legacy enrollment method is also a no-op", oldCore.hasEnrolledFingerprints());
    }

    private boolean platformBoolean(String method) throws Exception {
        // FingerprintManager was removed from the compileSdk37 public stubs; API26/28 still own it.
        // Invoke the real sandbox framework object, whose implementation is ShadowFingerprintManager.
        return (Boolean) frameworkFingerprint.getClass().getMethod(method).invoke(frameworkFingerprint);
    }

    private int strongAvailability() {
        // Do not mock/shadow AndroidX itself, inject its package-private test Injector, or weaken policy.
        return BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
    }
}
