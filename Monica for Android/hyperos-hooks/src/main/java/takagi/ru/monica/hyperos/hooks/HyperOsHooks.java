/*
 * HyperPasskey-derived compatibility hooks, GPL-3.0.
 * Upstream Howard20181/HyperPasskey da8569a9e955b9d361f08c11c73f42b044e9cdde.
 * Monica changes: explicit per-feature consent, owner-user/ROM/API gates, validated GMS
 * destinations, no ART memory writes, no hot reload, no vault access, fail-open discovery.
 * See app/src/main/assets/licenses/hyperos-builtins.txt.
 */
package takagi.ru.monica.hyperos.hooks;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.credentials.CredentialManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.credentials.CallingAppInfo;
import android.util.Log;
import androidx.annotation.NonNull;
import io.github.libxposed.api.XposedModule;
import org.luckypray.dexkit.DexKitCacheBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public final class HyperOsHooks extends XposedModule {
    private static final String TAG="MonicaHyperOS";
    private static final String GMS="com.google.android.gms";
    private static final ComponentName CHOOSER=new ComponentName(GMS,GMS+".identitycredentials.ui.CredentialChooserActivity");
    private static final ComponentName REMOTE=new ComponentName(GMS,GMS+".auth.api.credentials.credman.service.RemoteService");
    private SharedPreferences preferences;
    private final ReentrantLock internationalLock=new ReentrantLock(true);

    @Override public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        if(!HookPolicy.supported(Build.VERSION.SDK_INT,android.os.Process.myUid()/100000,Build.MANUFACTURER)) return;
        try { preferences=getRemotePreferences(HookPolicy.GROUP); }
        catch(RuntimeException unavailable){ note("preferences unavailable"); }
    }
    private boolean enabled(String key) {
        try {return preferences!=null && HookPolicy.enabled(preferences.getAll(),key);}
        catch(RuntimeException unavailable){return false;}
    }
    private void note(String stage){log(Log.WARN,TAG,stage);}
    private interface Install {void run() throws Exception;}
    private void install(String stage,Install operation){
        try {operation.run();} catch(Exception | LinkageError unavailable){note(stage+" unavailable");}
    }
    private void intercept(Executable method,String key,Hooker operation){
        hook(method).intercept(chain->enabled(key)?operation.intercept(chain):chain.proceed());
    }
    @Override public void onSystemServerStarting(@NonNull SystemServerStartingParam param){
        if(enabled(HookPolicy.CHOOSER) && Build.VERSION.SDK_INT>=35)
            install("chooser",()->chooser(param.getClassLoader()));
        if(enabled(HookPolicy.HYBRID)) install("hybrid",()->hybrid(param.getClassLoader()));
    }
    @Override public void onPackageReady(@NonNull PackageReadyParam param){
        if(!param.isFirstPackage())return;
        String option=HookPolicy.optionForPackage(param.getPackageName());
        if(option==null || !enabled(option))return;
        ClassLoader loader=param.getClassLoader();
        if(HookPolicy.HYBRID.equals(option)){
            install("scanner",()->scanner(loader));return;
        }
        install("package discovery",()->{
            System.loadLibrary("dexkit");
            DexKitCacheBridge.init(MemoryCache.INSTANCE);
            // Memory-only search cache. Process restart repeats discovery for the installed APK.
            try(var bridge=DexKitCacheBridge.create(param.getApplicationInfo().sourceDir,loader)){
                if(HookPolicy.RESET.equals(option))resetMethods(loader,bridge);
                else settings(loader,bridge);
            }
        });
    }
    private void resetMethods(ClassLoader loader,DexKitCacheBridge.RecyclableBridge bridge){
        var classes=new ArrayList<ClassData>();
        bridge.withBridge(raw->{
            for(String name:Arrays.asList("Lcom/miui/securitycenter/Application;","Lcom/miui/securitycenter/service/CacheService;")){
                var data=raw.getClassData(name);if(data!=null)classes.add(data);
            }
        });
        if(classes.isEmpty())return; // Never broaden a failed class lookup to the entire APK.
        install("autofill reset",()->{
            var writer=bridge.getMethod(FindMethod.create().searchInClass(classes).matcher(MethodMatcher.create()
                .anyOf(MethodMatcher.create().paramTypes(Context.class,String.class,int.class),MethodMatcher.create().paramTypes(String.class,int.class))
                .addInvoke("Landroid/content/res/Resources;->getString(I)Ljava/lang/String;")
                .addInvoke("Landroid/provider/Settings$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")));
            var target=bridge.getMethod(FindMethod.create().searchInClass(classes).matcher(MethodMatcher.create()
                .usingEqStrings("autofill_service").addInvoke(writer.toString()))).getMethodInstance(loader);
            if(target.getReturnType()!=void.class)return;
            deoptimize(writer.getMethodInstance(loader));
            intercept(target,HookPolicy.RESET,chain->null);
        });
        install("credential reset",()->{
            var writer=bridge.getMethod(FindMethod.create().searchInClass(classes).matcher(MethodMatcher.create()
                .anyOf(MethodMatcher.create().paramTypes(Context.class,String.class,int.class),MethodMatcher.create().paramTypes(String.class,int.class))
                .addInvoke("Landroid/content/res/Resources;->getStringArray(I)[Ljava/lang/String;")
                .addInvoke("Landroid/provider/Settings$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")));
            var target=bridge.getMethod(FindMethod.create().searchInClass(classes).matcher(MethodMatcher.create()
                .usingEqStrings("credential_service","credential_service_primary").addInvoke(writer.toString()))).getMethodInstance(loader);
            if(target.getReturnType()!=void.class)return;
            deoptimize(writer.getMethodInstance(loader));
            intercept(target,HookPolicy.RESET,chain->null);
        });
    }
    private void settings(ClassLoader loader,DexKitCacheBridge.RecyclableBridge bridge)throws Exception{
        Field international=loader.loadClass("miui.os.Build").getDeclaredField("IS_INTERNATIONAL_BUILD");
        international.setAccessible(true);
        if(international.getType()!=boolean.class)return;
        Hooker around=chain->{
            internationalLock.lock();
            try {
                final boolean previous;
                try {previous=international.getBoolean(null);}catch(Exception unavailable){return chain.proceed();}
                // Reflection only: do not scan or modify ART internal field offsets.
                return HookGuard.temporary(chain::proceed,()->international.setBoolean(null,true),()->international.setBoolean(null,previous));
            }finally{internationalLock.unlock();}
        };
        install("combined picker",()->{
            Method method=loader.loadClass("com.android.settings.applications.credentials.DefaultCombinedPicker").getDeclaredMethod("setDefaultKey",String.class);
            deoptimize(method);intercept(method,HookPolicy.SETTINGS,around);
        });
        install("combined provider",()->{
            Method method=loader.loadClass("com.android.settings.applications.credentials.DefaultCombinedPreferenceController").getDeclaredMethod("getCombinedProviderInfos",CredentialManager.class,int.class);
            deoptimize(method);intercept(method,HookPolicy.SETTINGS,around);
        });
        if(Build.VERSION.SDK_INT==34)install("default preference",()->{
            Method method=loader.loadClass("com.android.settings.applications.defaultapps.DefaultAppPreferenceController").getDeclaredMethod("updateState",loader.loadClass("androidx.preference.Preference"));
            deoptimize(method);intercept(method,HookPolicy.SETTINGS,around);
        });
        install("combined click",()->{
            var matcher=MethodMatcher.create().name("onLeftSideClicked").paramCount(0).anyOf(
                MethodMatcher.create().addInvoke("Lcom/android/settings/applications/credentials/CombinedProviderInfo;->launchSettingsActivityIntent(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;I)Z"),
                MethodMatcher.create().addInvoke("Lcom/android/settings/applications/credentials/CombinedProviderInfo;->launchSettingsActivityIntent(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;I)V"));
            for(var data:bridge.getMethods(FindMethod.create().searchPackages("com.android.settings.applications.credentials").matcher(matcher))){
                Method method=data.getMethodInstance(loader);deoptimize(method);intercept(method,HookPolicy.SETTINGS,around);
            }
        });
    }
    @SuppressWarnings("deprecation") private static boolean validComponent(Context context,ComponentName name,boolean service){
        try{
            PackageManager pm=context.getPackageManager();
            ComponentInfo info=service?pm.getServiceInfo(name,PackageManager.MATCH_SYSTEM_ONLY):pm.getActivityInfo(name,PackageManager.MATCH_SYSTEM_ONLY);
            return (info.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0 &&
                info.exported && HookPolicy.componentEnabled(info.applicationInfo.enabled,info.enabled,pm.getComponentEnabledSetting(name));
        }catch(Exception unavailable){return false;}
    }
    private static boolean ownerContext(Context context){
        try{return (int)Context.class.getMethod("getUserId").invoke(context)==0;}
        catch(Exception unavailable){return false;}
    }
    private static Context systemContext(){
        try{
            Class<?> thread=Class.forName("android.app.ActivityThread");
            Object current=thread.getMethod("currentActivityThread").invoke(null);
            return (Context)thread.getMethod("getSystemContext").invoke(current);
        }catch(Exception unavailable){return null;}
    }
    private void chooser(ClassLoader loader)throws Exception{
        Class<?> factory=loader.loadClass("android.credentials.selection.IntentFactory");
        Class<?> builder=loader.loadClass("android.credentials.selection.IntentCreationResult$Builder");
        Method method=Build.VERSION.SDK_INT>=36?factory.getDeclaredMethod("getOemOverrideComponentName",Context.class,builder,int.class):
            factory.getDeclaredMethod("getOemOverrideComponentName",Context.class,builder);
        Method packageName=builder.getMethod("setOemUiPackageName",String.class);
        Class<?> usage=loader.loadClass("android.credentials.selection.IntentCreationResult$OemUiUsageStatus");
        Method status=builder.getMethod("setOemUiUsageStatus",usage);
        Object success=usage.getField("SUCCESS").get(null);
        intercept(method,HookPolicy.CHOOSER,chain->{
            if(!(chain.getArg(0) instanceof Context context) || !ownerContext(context) ||
                (chain.getArgs().size()>2 && !Integer.valueOf(0).equals(chain.getArg(2))) || !validComponent(context,CHOOSER,false))return chain.proceed();
            try{
                Object result=chain.getArg(1);packageName.invoke(result,GMS);status.invoke(result,success);return CHOOSER;
            }catch(Exception incompatible){return chain.proceed();}
        });
    }
    private void hybrid(ClassLoader loader)throws Exception{
        Class<?> request=loader.loadClass("com.android.server.credentials.RequestSession");
        Class<?> lifetime=loader.loadClass("com.android.server.credentials.RequestSession$SessionLifetime");
        Field hybrid=request.getDeclaredField("mHybridService");hybrid.setAccessible(true);
        if(hybrid.getType()!=String.class)return;
        Class<?>[] types=Build.VERSION.SDK_INT>=35?new Class<?>[]{Context.class,lifetime,Object.class,int.class,int.class,Object.class,Object.class,String.class,CallingAppInfo.class,Set.class,CancellationSignal.class,long.class,boolean.class}:
            new Class<?>[]{Context.class,lifetime,Object.class,int.class,int.class,Object.class,Object.class,String.class,CallingAppInfo.class,Set.class,CancellationSignal.class,long.class};
        Constructor<?> constructor=request.getDeclaredConstructor(types);
        for(String name:Arrays.asList("ProviderGetSession","ProviderCreateSession"))install("provider deoptimization",()->{
            for(Method method:loader.loadClass("com.android.server.credentials."+name).getDeclaredMethods())if(method.getName().equals("createNewSession"))deoptimize(method);
        });
        intercept(constructor,HookPolicy.HYBRID,chain->{
            if(!(chain.getArg(0) instanceof Context context) || !ownerContext(context) || !Integer.valueOf(0).equals(chain.getArg(3)) ||
                !validComponent(context,REMOTE,true))return chain.proceed();
            return HookGuard.after(chain::proceed,()->hybrid.set(chain.getThisObject(),REMOTE.flattenToShortString()));
        });
    }
    private void scanner(ClassLoader loader)throws Exception{
        Method method=loader.loadClass("com.xiaomi.scanner.module.code.utils.bean.MiFiDoBean").getDeclaredMethod("getAppPackageName");
        if(method.getReturnType()!=String.class)return;
        intercept(method,HookPolicy.HYBRID,chain->{
            Context context=systemContext();
            if(context==null || !ownerContext(context) || !validComponent(context,REMOTE,true))return chain.proceed();
            return "";
        });
    }
}
