package com.redactado.noray.pkg;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A semantic version {@code MAJOR.MINOR.PATCH[-PRERELEASE]}, ordered by the semver precedence rules (no build metadata). */
public record Version(int major, int minor, int patch, String preRelease) implements Comparable<Version> {

    private static final Pattern FORMAT = Pattern.compile(
            "(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");

    public Version {
        preRelease = preRelease == null ? "" : preRelease;
    }

    public static Version parse(String text) {
        Matcher m = FORMAT.matcher(text == null ? "" : text);
        if (!m.matches() || text.length() > 64) {
            throw new PackageException(PackageException.Reason.MALFORMED, "not a version (MAJOR.MINOR.PATCH[-PRE]): " + text);
        }
        return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                m.group(4));
    }

    @Override
    public int compareTo(Version o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) c = Integer.compare(minor, o.minor);
        if (c == 0) c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        if (preRelease.isEmpty() || o.preRelease.isEmpty()) {
            return Boolean.compare(preRelease.isEmpty(), o.preRelease.isEmpty()); // a release outranks its pre-releases
        }
        String[] a = preRelease.split("\\."), b = o.preRelease.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            boolean na = a[i].chars().allMatch(Character::isDigit), nb = b[i].chars().allMatch(Character::isDigit);
            int r;
            if (na && nb) r = Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]));
            else if (na != nb) r = na ? -1 : 1; // numeric identifiers sort first
            else r = a[i].compareTo(b[i]);
            if (r != 0) return r;
        }
        return Integer.compare(a.length, b.length);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease.isEmpty() ? "" : "-" + preRelease);
    }
}
