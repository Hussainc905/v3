package com.elyastech.lightcontrol;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Mirror of the ELYAS screen V1.6 ASCII serial protocol. */
public final class ProtocolState {
    public static final int SKIN = 0x01;
    public static final int PAIR1 = 0x06;
    public static final int PAIR2 = 0x18;
    public static final int PAIR3 = 0x20;
    public static final int FOUR = 0x1E;
    public static final int SIX = 0x3E;
    public static final int LED = 0x40;

    public int requestedBits = 0;
    public int mode = 0;  // 0 manual / 1 flash / 2 wave / 3 auto
    public int speedMs = 200;
    public int localRadioReady = 0;
    public long lastAckSequence = 0;
    public int lastAckBits = 0;
    public long ackAgeMs = -1;
    public boolean hasState = false;
    public boolean ackReceivedThisConnection = false;

    public static boolean enabled(int bits, int mask) {
        return (bits & mask) == mask;
    }

    public void resetForNewConnection() {
        requestedBits = 0;
        mode = 0;
        speedMs = 200;
        localRadioReady = 0;
        lastAckSequence = 0;
        lastAckBits = 0;
        ackAgeMs = -1;
        hasState = false;
        ackReceivedThisConnection = false;
    }

    /** True if a STATE or ACK line was parsed. Other lines are handled by the UI. */
    public boolean consume(String raw) {
        if (raw == null) return false;
        String line = raw.trim();
        boolean state = line.startsWith("STATE ");
        boolean ack = line.startsWith("ACK ");
        if (!state && !ack) return false;

        Map<String, String> fields = new HashMap<>();
        for (String token : line.split("\\s+")) {
            int equal = token.indexOf('=');
            if (equal > 0 && equal < token.length() - 1) {
                fields.put(token.substring(0, equal).toUpperCase(Locale.US), token.substring(equal + 1));
            }
        }
        if (state) {
            requestedBits = hex(fields.get("BITS"), requestedBits);
            mode = bounded(decimal(fields.get("MODE"), mode), 0, 3);
            speedMs = bounded(decimal(fields.get("SPEED"), speedMs), 100, 3000);
            localRadioReady = decimal(fields.get("RADIO"), localRadioReady);
            lastAckSequence = unsigned(fields.get("ACKSEQ"), lastAckSequence);
            lastAckBits = hex(fields.get("ACKBITS"), lastAckBits);
            ackAgeMs = unsigned(fields.get("ACKAGE"), ackAgeMs);
            hasState = true;
        } else {
            lastAckSequence = unsigned(fields.get("SEQ"), lastAckSequence);
            lastAckBits = hex(fields.get("BITS"), lastAckBits);
            // ACK indicates receiver decoded a command, not measured lamp current.
            ackAgeMs = 0;
        }
        if (lastAckSequence != 0) ackReceivedThisConnection = true;
        return true;
    }

    static int bounded(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    static int decimal(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return fallback; }
    }

    static long unsigned(String s, long fallback) {
        if (s == null) return fallback;
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) { return fallback; }
    }

    static int hex(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s, 16) & 0xFF; } catch (NumberFormatException ignored) { return fallback; }
    }
}
