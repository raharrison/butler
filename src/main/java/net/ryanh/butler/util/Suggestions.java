package net.ryanh.butler.util;

import java.util.Collection;
import java.util.Locale;

/**
 * "did you mean" hints for unknown keys, functions and names. Most config mistakes are typos, so
 * these earn their keep.
 */
public final class Suggestions {

    private Suggestions() {
    }

    /**
     * @return a " (did you mean \"x\"?)" fragment, or an empty string if nothing is close
     */
    public static String from(String actual, Collection<String> candidates) {
        String best = closest(actual, candidates);
        return best == null ? "" : " (did you mean \"" + best + "\"?)";
    }

    /**
     * @return the closest candidate within tolerance, or null
     */
    public static String closest(String actual, Collection<String> candidates) {
        if (actual == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String needle = actual.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate.equals(actual)) {
                continue;
            }
            int d = distance(needle, candidate.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        // Tolerance scales with length: "usse" -> "uses" is obvious, "a" -> "zzz" is not.
        int tolerance = Math.max(1, Math.min(3, best.length() / 3));
        return bestDistance <= tolerance ? best : null;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = curr;
            curr = t;
        }
        return prev[b.length()];
    }
}
