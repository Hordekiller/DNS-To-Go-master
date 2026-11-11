package com.hololo.app.dnschanger.dns;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppRoutingPolicyTest {
    private static final String SELF = "com.hololo.app.dnschanger";

    /** Behaves like VpnService.Builder: mixing allow and disallow throws. */
    static final class StrictBuilder implements AppRoutingPolicy.Target {
        final Set<String> installed;
        final List<String> allowed = new ArrayList<>();
        final List<String> disallowed = new ArrayList<>();

        StrictBuilder(String... installed) {
            this.installed = new HashSet<>(Arrays.asList(installed));
        }

        @Override
        public boolean addAllowed(String pkg) {
            if (!disallowed.isEmpty()) throw new UnsupportedOperationException("addDisallowedApplication already called");
            if (!installed.contains(pkg)) return false;
            allowed.add(pkg);
            return true;
        }

        @Override
        public boolean addDisallowed(String pkg) {
            if (!allowed.isEmpty()) throw new UnsupportedOperationException("addAllowedApplication already called");
            if (!installed.contains(pkg)) return false;
            disallowed.add(pkg);
            return true;
        }
    }

    /**
     * Regression: the service called addAllowedApplication() for the selected apps and
     * then unconditionally addDisallowedApplication(self), which throws and killed
     * the tunnel whenever the user had selected any app.
     */
    @Test
    public void allowlistNeverCallsDisallow() {
        StrictBuilder b = new StrictBuilder(SELF, "org.telegram", "com.android.chrome");
        AppRoutingPolicy.Result r = AppRoutingPolicy.apply(b,
                new HashSet<>(Arrays.asList("org.telegram", "com.android.chrome")), SELF);
        assertEquals(AppRoutingPolicy.Mode.ALLOWLIST, r.mode);
        assertEquals(Arrays.asList("com.android.chrome", "org.telegram"), b.allowed);
        assertTrue(b.disallowed.isEmpty());
    }

    @Test
    public void ownPackageIsNeverAllowlisted() {
        StrictBuilder b = new StrictBuilder(SELF, "org.telegram");
        AppRoutingPolicy.apply(b, new HashSet<>(Arrays.asList(SELF, "org.telegram")), SELF);
        assertEquals(Arrays.asList("org.telegram"), b.allowed);
    }

    @Test
    public void noSelectionExcludesOnlySelf() {
        StrictBuilder b = new StrictBuilder(SELF);
        AppRoutingPolicy.Result r = AppRoutingPolicy.apply(b, new HashSet<String>(), SELF);
        assertEquals(AppRoutingPolicy.Mode.DENYLIST, r.mode);
        assertEquals(Arrays.asList(SELF), b.disallowed);
        AppRoutingPolicy.apply(new StrictBuilder(SELF), null, SELF);
    }

    /** All selected apps uninstalled: must not end up routing *everything incl. us*. */
    @Test
    public void uninstalledSelectionFallsBackToDenylistOfSelf() {
        StrictBuilder b = new StrictBuilder(SELF);
        AppRoutingPolicy.Result r = AppRoutingPolicy.apply(b, new HashSet<>(Arrays.asList("gone.app")), SELF);
        assertEquals(AppRoutingPolicy.Mode.DENYLIST, r.mode);
        assertEquals(Arrays.asList(SELF), b.disallowed);
        assertTrue(r.missing.contains("gone.app"));
    }
}
