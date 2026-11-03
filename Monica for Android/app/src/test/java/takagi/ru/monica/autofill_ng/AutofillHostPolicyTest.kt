package takagi.ru.monica.autofill_ng

import org.junit.Assert.*
import org.junit.Test

class AutofillHostPolicyTest {
    @Test fun ipv4HostRecoveryPreservesTheWholeAddress() {
        for (value in listOf("192.168.1.1", "192.168.1.1:8080", "http://192.168.1.1:8080/login?q=1")) {
            assertEquals(value, "192.168.1.1", AutofillHostPolicy.host(value))
        }
        assertEquals(8080, AutofillHostPolicy.address("http://192.168.1.1:8080/login")?.port)
        assertEquals("/login", AutofillHostPolicy.address("http://192.168.1.1:8080/login")?.encodedPath)
    }

    @Test fun ipv6EquivalentRepresentationsHaveOneCanonicalHost() {
        for (value in listOf("fd00::1", "[fd00::1]", "[fd00::1]:8080", "http://[fd00:0:0:0:0:0:0:1]:8080/login")) {
            assertEquals(value, "fd00::1", AutofillHostPolicy.host(value))
        }
        assertEquals(8080, AutofillHostPolicy.address("[fd00::1]:8080")?.port)
        assertEquals(443, AutofillHostPolicy.address("fd00::1")?.port)
        assertEquals("::1", AutofillHostPolicy.host("::1"))
        assertEquals("http://[fd00::1]", AutofillHostPolicy.origin("http", "fd00::1"))
        assertEquals("http://192.168.1.1", AutofillHostPolicy.origin("http", "192.168.1.1"))
    }

    @Test fun malformedAndAmbiguousIpLiteralsAreRejected() {
        for (value in listOf("3.14", "http://999.168.1.1", "192.168.01.1", "127.1", "http://[fd00:::1]", "[fd00::1]:99999", "fe80::1%wlan0")) {
            assertNull(value, AutofillHostPolicy.host(value))
        }
    }

    @Test fun userInfoAndNonWebSchemesCannotSupplyAHost() {
        for (value in listOf("http://192.168.1.1@evil.example", "http://@192.168.1.1", "javascript:192.168.1.1", "file:///192.168.1.1", "androidapp://com.example.app", "http://192.168.1.1\n.evil.example")) {
            assertNull(value, AutofillHostPolicy.host(value))
        }
    }

    @Test fun domainsAndInternationalNamesStillNormalize() {
        assertEquals("nas.example.com", AutofillHostPolicy.host("https://NAS.Example.com./login"))
        assertEquals("xn--fsqu00a.xn--0zwm56d", AutofillHostPolicy.host("https://例子.测试"))
        assertEquals("nas.local", AutofillHostPolicy.host("http://nas.local:8080"))
        assertFalse(AutofillHostPolicy.isIp("nas.local"))
    }
}
