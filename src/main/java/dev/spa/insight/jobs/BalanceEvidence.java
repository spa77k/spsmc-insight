package dev.spa.insight.jobs;

import java.math.BigDecimal;

/** Verification is a later balance observation, NOT an economy transaction receipt. */
public final class BalanceEvidence {
    private BalanceEvidence() {}
    public static String status(boolean async, boolean overlap, BigDecimal expected, BigDecimal observed) {
        if (async) return "unverified_async";
        if (overlap) return "ambiguous";
        if (expected == null || observed == null) return "unverified";
        return expected.compareTo(observed) == 0 ? "balance_verified" : "balance_mismatch";
    }
    public static boolean jobsPaymentOrigin(StackTraceElement[] stack) {
        boolean task = false, vault = false;
        int setters = 0;
        for (StackTraceElement frame : stack) {
            task |= frame.getClassName().equals("com.gamingmesh.jobs.tasks.BufferedPaymentTask");
            vault |= frame.getClassName().equals("com.gamingmesh.jobs.economy.VaultEconomy")
                    && (frame.getMethodName().equals("depositPlayer") || frame.getMethodName().equals("withdrawPlayer"));
            // Nested balance updates from another listener must not inherit Jobs attribution.
            if (frame.getClassName().equals("com.earth2me.essentials.User")
                    && frame.getMethodName().equals("setMoney")) setters++;
        }
        return task && vault && setters == 1;
    }
}
