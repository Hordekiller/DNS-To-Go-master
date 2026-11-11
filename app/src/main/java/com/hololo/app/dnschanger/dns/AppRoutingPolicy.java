package com.hololo.app.dnschanger.dns;

import java.util.Set;
import java.util.TreeSet;

/**
 * Decides which apps go through the tunnel.
 *
 * <p>{@code VpnService.Builder} throws {@code UnsupportedOperationException} if both
 * {@code addAllowedApplication} and {@code addDisallowedApplication} are called on the
 * same builder, so exactly one mode is used:
 * <ul>
 *   <li><b>Allowlist</b>: the user selected apps. Only those are routed; our own app is
 *       never added (it is then implicitly outside the VPN, which is what keeps the
 *       DoH bootstrap lookup and upstream sockets from looping back into the tunnel).</li>
 *   <li><b>Denylist</b>: no selection, or none of the selected apps are installed any
 *       more. Everything is routed except our own package.</li>
 * </ul>
 */
public final class AppRoutingPolicy {

    public interface Target {
        /** @return false if the package is not installed. */
        boolean addAllowed(String packageName);

        /** @return false if the package is not installed. */
        boolean addDisallowed(String packageName);
    }

    public enum Mode { ALLOWLIST, DENYLIST }

    public static final class Result {
        public final Mode mode;
        public final Set<String> applied;
        public final Set<String> missing;

        Result(Mode mode, Set<String> applied, Set<String> missing) {
            this.mode = mode;
            this.applied = applied;
            this.missing = missing;
        }
    }

    private AppRoutingPolicy() {}

    public static Result apply(Target target, Set<String> selectedApps, String ownPackage) {
        Set<String> applied = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        if (selectedApps != null) {
            for (String pkg : new TreeSet<>(selectedApps)) {
                if (pkg == null || pkg.isEmpty() || pkg.equals(ownPackage)) continue;
                if (target.addAllowed(pkg)) {
                    applied.add(pkg);
                } else {
                    missing.add(pkg);
                }
            }
        }
        if (!applied.isEmpty()) {
            return new Result(Mode.ALLOWLIST, applied, missing);
        }
        // No usable allowlist entries were added, so calling addDisallowed is legal.
        if (target.addDisallowed(ownPackage)) applied.add(ownPackage);
        return new Result(Mode.DENYLIST, applied, missing);
    }
}
