package io.github.libxposed.service;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real pinned service library; fake transport is not a live LSPosed claim. */
public class MonicaFrameworkPreferencesTest {
    private XposedService remote(boolean reject, Map<String,Object> stored) {
        IXposedService transport=(IXposedService)Proxy.newProxyInstance(IXposedService.class.getClassLoader(),
            new Class<?>[]{IXposedService.class},(proxy,method,args)->{
                if(method.getName().equals("requestRemotePreferences")) {
                    Bundle result=new Bundle();result.putSerializable("map",new HashMap<>(stored));return result;
                }
                if(method.getName().equals("updateRemotePreferences")) {
                    if(reject)throw new RemoteException("synthetic rejection");
                    Bundle update=(Bundle)args[1];
                    stored.putAll((Map<String,Object>)update.getSerializable("put"));return null;
                }
                if(method.getName().equals("getApiVersion"))return 102;
                throw new AssertionError("Unexpected fake transport call: "+method.getName());
            });
        return new XposedService(transport);
    }
    @Test public void failedCommitCannotAppearEnabledAfterFreshRead() {
        Map<String,Object> stored=new HashMap<>();XposedService service=remote(true,stored);
        SharedPreferences first=MonicaFrameworkPreferences.read(service,"synthetic");
        assertFalse(first.edit().putBoolean("enabled",true).commit());
        assertTrue("Pinned library updates its local cache before remote commit",first.getBoolean("enabled",false));
        assertFalse(MonicaFrameworkPreferences.read(service,"synthetic").getBoolean("enabled",false));
        assertTrue(stored.isEmpty());
    }
    @Test public void confirmedCommitAndLaterExternalChangeReadFromTransport() {
        Map<String,Object> stored=new HashMap<>();XposedService service=remote(false,stored);
        assertTrue(MonicaFrameworkPreferences.read(service,"synthetic").edit().putBoolean("enabled",true).commit());
        assertTrue(MonicaFrameworkPreferences.read(service,"synthetic").getBoolean("enabled",false));
        stored.put("enabled",false);
        assertFalse(MonicaFrameworkPreferences.read(service,"synthetic").getBoolean("enabled",true));
    }
}
