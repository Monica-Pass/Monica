package takagi.ru.monica.hyperos.hooks;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class HookPolicyTest {
    @Test public void defaultsUnknownKeysAndWrongUsersStayOff() {
        assertFalse(HookPolicy.enabled(Map.of(), HookPolicy.RESET));
        assertFalse(HookPolicy.enabled(Map.of("user_id",10,HookPolicy.RESET,true), HookPolicy.RESET));
        assertFalse(HookPolicy.enabled(Map.of("user_id",0,"unknown",true), "unknown"));
        assertFalse(HookPolicy.enabled(Map.of("user_id",0,HookPolicy.RESET,"true"), HookPolicy.RESET));
    }
    @Test public void switchesDoNotEnableEachOther() {
        for(String key:HookPolicy.KEYS) for(String requested:HookPolicy.KEYS)
            assertEquals(key.equals(requested),HookPolicy.enabled(Map.of("user_id",0,key,true),requested));
    }
    @Test public void incompatiblePlatformAndSecondaryUsersAreExcluded() {
        assertTrue(HookPolicy.supported(36,0,"Xiaomi"));
        for(int api:new int[]{26,33,37,100}) assertFalse(HookPolicy.supported(api,0,"Xiaomi"));
        assertFalse(HookPolicy.supported(35,10,"Xiaomi"));
        assertFalse(HookPolicy.supported(35,0,"Google"));
        assertFalse(HookPolicy.supported(35,0,null));
    }
    @Test public void scopeNeverIncludesMonicaOrUnrelatedPackages() {
        assertNull(HookPolicy.optionForPackage("takagi.ru.monica"));
        assertNull(HookPolicy.optionForPackage("com.example.other"));
        assertEquals(HookPolicy.RESET,HookPolicy.optionForPackage("com.miui.securitycenter"));
    }
    @Test public void disabledUntilUsedAndDisabledUserOverridesAreRejected() {
        for(int state:new int[]{2,3,4}) assertFalse(HookPolicy.componentEnabled(true,true,state));
        assertFalse(HookPolicy.componentEnabled(false,true,1));
        assertTrue(HookPolicy.componentEnabled(true,false,1));
        assertFalse(HookPolicy.componentEnabled(true,false,0));
    }
    @Test public void originalFailureIsNotSwallowedOrRepeated() throws Throwable {
        int[] calls={0};RuntimeException failure=new RuntimeException("synthetic");
        try { HookGuard.after(()->{calls[0]++;throw failure;},()->fail());fail(); }
        catch(RuntimeException actual){assertSame(failure,actual);}
        assertEquals(1,calls[0]);
    }
    @Test public void fieldFailurePreservesOriginalResult() throws Throwable {
        int[] calls={0};Object expected=new Object();
        assertSame(expected,HookGuard.after(()->{calls[0]++;return expected;},()->{throw new IllegalAccessException();}));
        assertEquals(1,calls[0]);
    }
    @Test public void temporaryStateRestoredEvenWhenOriginalThrows() throws Throwable {
        boolean[] state={false};RuntimeException failure=new RuntimeException("synthetic");
        try {HookGuard.temporary(()->{assertTrue(state[0]);throw failure;},()->state[0]=true,()->state[0]=false);fail();}
        catch(RuntimeException actual){assertSame(failure,actual);}
        assertFalse(state[0]);
    }
    @Test public void unsupportedFieldRunsOriginalExactlyOnce() throws Throwable {
        int[] calls={0};
        HookGuard.temporary(()->{calls[0]++;return null;},()->{throw new IllegalAccessException();},()->fail());
        assertEquals(1,calls[0]);
    }
}
