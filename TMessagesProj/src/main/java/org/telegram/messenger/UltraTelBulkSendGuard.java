package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Local, per-account guard for explicitly confirmed UltraTel Beta bulk sends.
 * Call markCompleted only after a campaign has actually been dispatched.
 * This is a client-side safeguard, not a server-enforced anti-spam guarantee.
 */
public final class UltraTelBulkSendGuard {
    public static final long COOLDOWN_MS = 24L * 60L * 60L * 1000L;
    public static final int MAX_RECIPIENTS = 20;
    private static final String PREFS = "ultratel_bulk_send";
    private static final String LAST_PREFIX = "last_completed_";

    private UltraTelBulkSendGuard() {}

    public static boolean isEligibleRecipient(long dialogId) {
        // Private user dialogs only; exclude groups, channels and special IDs.
        return dialogId > 0 && dialogId != Long.MAX_VALUE;
    }

    public static boolean isWithinRecipientLimit(int count) {
        return count > 0 && count <= MAX_RECIPIENTS;
    }

    public static synchronized long remainingCooldownMs(Context context, int account) {
        if (context == null || account < 0) {
            return COOLDOWN_MS;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(LAST_PREFIX + account, 0L);
        if (last <= 0) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - last;
        // A clock moved backwards must not bypass the cooldown.
        if (elapsed < 0) {
            return COOLDOWN_MS;
        }
        return Math.max(0L, COOLDOWN_MS - elapsed);
    }

    public static synchronized boolean canStart(Context context, int account, int recipientCount) {
        return BuildVars.isBetaApp()
                && isWithinRecipientLimit(recipientCount)
                && remainingCooldownMs(context, account) == 0L;
    }

    public static synchronized boolean markCompleted(Context context, int account) {
        if (context == null || account < 0 || !BuildVars.isBetaApp()
                || remainingCooldownMs(context, account) > 0L) {
            return false;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(LAST_PREFIX + account, System.currentTimeMillis()).commit();
    }
}
