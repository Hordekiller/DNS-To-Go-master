package com.hololo.app.dnschanger.resolver;

/** Validates IP literals without ever triggering a DNS lookup. */
public final class IpLiterals {
    private IpLiterals() {}

    public static boolean isIpLiteral(String s) {
        return isIpv4(s) || isIpv6(s);
    }

    public static boolean isIpv4(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i)) || part.charAt(i) > '9') return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') return false; // no octal ambiguity
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    public static boolean isIpv6(String s) {
        if (s == null || s.indexOf(':') < 0 || s.length() > 45) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == ':' || c == '.';
            if (!ok) return false; // also rejects zone ids and hostnames
        }
        int doubleColon = s.indexOf("::");
        if (doubleColon >= 0 && s.indexOf("::", doubleColon + 1) >= 0) return false;
        String body = s;
        int groupsNeeded = 8;
        int lastColon = s.lastIndexOf(':');
        String tail = s.substring(lastColon + 1);
        if (tail.indexOf('.') >= 0) {
            if (!isIpv4(tail)) return false;
            body = s.substring(0, lastColon + 1) + "0:0";
        }
        String[] groups = body.split(":", -1);
        int nonEmpty = 0;
        for (int i = 0; i < groups.length; i++) {
            String g = groups[i];
            if (g.isEmpty()) continue;
            if (g.length() > 4 || g.indexOf('.') >= 0) return false;
            nonEmpty++;
        }
        if (doubleColon >= 0) return nonEmpty < groupsNeeded;
        if (groups.length != groupsNeeded) return false;
        for (String g : groups) if (g.isEmpty()) return false;
        return true;
    }
}
