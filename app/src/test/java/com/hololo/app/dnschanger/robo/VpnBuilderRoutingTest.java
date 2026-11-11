package com.hololo.app.dnschanger.robo;

import static org.junit.Assert.*;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Parcel;

import androidx.test.core.app.ApplicationProvider;

import com.hololo.app.dnschanger.dns.AppRoutingPolicy;
import com.hololo.app.dnschanger.model.DNSModel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Runs against the real framework VpnService.Builder (Robolectric android-all). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class,
        shadows = VpnBuilderRoutingTest.ShadowIPackageManagerStub.class)
public class VpnBuilderRoutingTest {
    private VpnService service;
    private String self;

    @Before
    public void setUp() throws Exception {
        service = Robolectric.buildService(VpnService.class).create().get();
        self = service.getPackageName();
        PackageInfo telegram = new PackageInfo();
        telegram.packageName = "org.telegram.messenger";
        telegram.applicationInfo = new android.content.pm.ApplicationInfo();
        telegram.applicationInfo.packageName = telegram.packageName;
        Shadows.shadowOf(ApplicationProvider.getApplicationContext().getPackageManager()).installPackage(telegram);
        installPackageManagerBinder(new HashSet<>(Arrays.asList(self, "org.telegram.messenger")));
    }

    /**
     * VpnService.Builder.verifyApp() talks to the IPackageManager binder directly,
     * which Robolectric does not provide; back it with a proxy that knows which
     * packages are "installed".
     */
    private static void installPackageManagerBinder(java.util.Set<String> installed) throws Exception {
        Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
        android.os.Binder binder = new android.os.Binder();
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(ipm.getClassLoader(),
                new Class<?>[]{ipm, android.os.IInterface.class},
                (p, method, args) -> {
                    if (method.getName().equals("asBinder")) return binder;
                    if (method.getName().equals("getApplicationInfo") && args != null && args.length > 0) {
                        String pkg = (String) args[0];
                        if (!installed.contains(pkg)) return null;
                        android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo();
                        info.packageName = pkg;
                        return info;
                    }
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) return false;
                    if (r == int.class) return 0;
                    if (r == long.class) return 0L;
                    return null;
                });
        ShadowIPackageManagerStub.packageManager = proxy;
    }

    /** Hands our proxy to VpnService.Builder.verifyApp(). */
    @org.robolectric.annotation.Implements(className = "android.content.pm.IPackageManager$Stub", isInAndroidSdk = false)
    public static class ShadowIPackageManagerStub {
        static volatile Object packageManager;

        @org.robolectric.annotation.Implementation
        protected static Object asInterface(android.os.IBinder binder) {
            return packageManager;
        }
    }

    /** Reproduces the v2.6.8 sequence: allowlist the selected apps, then disallow self. */
    @Test
    public void originalSequenceThrowsInTheFramework() throws Exception {
        VpnService.Builder builder = service.new Builder();
        builder.addAllowedApplication("org.telegram.messenger");
        try {
            builder.addDisallowedApplication(self);
            fail("framework must reject mixing allowed and disallowed applications");
        } catch (UnsupportedOperationException expected) {
            // this exception escaped into the tunnel thread and killed the VPN
        }
    }

    @Test
    public void policyIsAcceptedByTheFrameworkBuilder() {
        for (java.util.Set<String> selection : Arrays.asList(
                new HashSet<>(Collections.singletonList("org.telegram.messenger")),
                new HashSet<>(Arrays.asList("org.telegram.messenger", self, "uninstalled.app")),
                new HashSet<>(Collections.singletonList("uninstalled.app")),
                new HashSet<String>())) {
            VpnService.Builder builder = service.new Builder();
            AppRoutingPolicy.apply(new AppRoutingPolicy.Target() {
                @Override public boolean addAllowed(String pkg) {
                    try { builder.addAllowedApplication(pkg); return true; }
                    catch (PackageManager.NameNotFoundException e) { return false; }
                }
                @Override public boolean addDisallowed(String pkg) {
                    try { builder.addDisallowedApplication(pkg); return true; }
                    catch (PackageManager.NameNotFoundException e) { return false; }
                }
            }, selection, self); // must not throw for any selection
        }
    }

    @Test
    public void dnsModelParcelRoundTripKeepsGroupAndProtocol() {
        DNSModel m = new DNSModel();
        m.setName("Quad9");
        m.setServerGroupId("quad9");
        m.setBestProtocol("DoT");
        m.setFirstDns("9.9.9.9");
        m.setFeatures(Arrays.asList("DoH", "DoT", "UDP"));
        Parcel p = Parcel.obtain();
        m.writeToParcel(p, 0);
        p.setDataPosition(0);
        DNSModel back = DNSModel.CREATOR.createFromParcel(p);
        p.recycle();
        assertEquals("quad9", back.getServerGroupId());
        assertEquals("DoT", back.getBestProtocol());
        assertEquals("9.9.9.9", back.getFirstDns());
        assertEquals(3, back.getFeatures().size());
    }
}
