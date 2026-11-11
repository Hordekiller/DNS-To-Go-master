package com.hololo.app.dnschanger.dns;

import java.util.Locale;

/**
 * Pure-Java helpers for RFC 1035 DNS messages. No Android dependencies so it can be
 * unit-tested on the JVM.
 *
 * <p>All methods are defensive: malformed input yields {@code null} / {@code false} /
 * {@link #NOT_CACHEABLE} rather than throwing.
 */
public final class DnsMessage {

    public static final int HEADER_LENGTH = 12;
    public static final int MAX_NAME_LENGTH = 255;   // RFC 1035 2.3.4 (wire octets)
    public static final int MAX_LABEL_LENGTH = 63;
    public static final int TYPE_SOA = 6;
    public static final int TYPE_OPT = 41;
    public static final int RCODE_NOERROR = 0;
    public static final int RCODE_FORMERR = 1;
    public static final int RCODE_SERVFAIL = 2;
    public static final int RCODE_NXDOMAIN = 3;
    public static final int RCODE_REFUSED = 5;
    /** Returned by {@link #cacheTtlSeconds(byte[])} when a response must not be cached. */
    public static final long NOT_CACHEABLE = -1L;

    private DnsMessage() {}

    /** First question of a message. */
    public static final class Question {
        /** Lower-cased, dot-separated, without trailing dot. Root is "". */
        public final String name;
        public final int type;
        public final int qclass;
        /** Offset of the first byte after the question section entry. */
        public final int end;

        Question(String name, int type, int qclass, int end) {
            this.name = name;
            this.type = type;
            this.qclass = qclass;
            this.end = end;
        }
    }

    public static int id(byte[] msg) {
        return u16(msg, 0);
    }

    public static int flags(byte[] msg) {
        return u16(msg, 2);
    }

    public static int rcode(byte[] msg) {
        return flags(msg) & 0x000F;
    }

    public static boolean isResponse(byte[] msg) {
        return (flags(msg) & 0x8000) != 0;
    }

    public static boolean isTruncated(byte[] msg) {
        return (flags(msg) & 0x0200) != 0;
    }

    public static int qdCount(byte[] msg) { return u16(msg, 4); }
    public static int anCount(byte[] msg) { return u16(msg, 6); }
    public static int nsCount(byte[] msg) { return u16(msg, 8); }
    public static int arCount(byte[] msg) { return u16(msg, 10); }

    /** Returns a copy of {@code msg} with its transaction ID replaced. */
    public static byte[] withId(byte[] msg, int id) {
        byte[] copy = msg.clone();
        if (copy.length >= 2) {
            copy[0] = (byte) (id >> 8);
            copy[1] = (byte) id;
        }
        return copy;
    }

    /** Parses the first question. Returns null for anything malformed. */
    public static Question parseQuestion(byte[] msg) {
        if (msg == null || msg.length < HEADER_LENGTH || qdCount(msg) < 1) return null;
        StringBuilder name = new StringBuilder();
        int end = readName(msg, HEADER_LENGTH, name);
        if (end < 0 || end + 4 > msg.length) return null;
        int type = u16(msg, end);
        int qclass = u16(msg, end + 2);
        return new Question(name.toString().toLowerCase(Locale.US), type, qclass, end + 4);
    }

    /**
     * Decodes a (possibly compressed) domain name starting at {@code offset}.
     *
     * @return the offset just past the name in the original position (i.e. after the
     *         first pointer, if any), or -1 if malformed (bad pointer, loop, overlong).
     */
    public static int readName(byte[] msg, int offset, StringBuilder out) {
        int pos = offset;
        int resumeAt = -1;
        int wireLength = 0;
        int jumps = 0;
        while (true) {
            if (pos < 0 || pos >= msg.length) return -1;
            int len = msg[pos] & 0xFF;
            if (len == 0) {
                pos += 1;
                break;
            }
            int kind = len & 0xC0;
            if (kind == 0xC0) {
                if (pos + 1 >= msg.length) return -1;
                int target = ((len & 0x3F) << 8) | (msg[pos + 1] & 0xFF);
                // Pointers must point strictly backwards; this also bounds loops.
                if (target >= pos || ++jumps > 64) return -1;
                if (resumeAt < 0) resumeAt = pos + 2;
                pos = target;
                continue;
            }
            if (kind != 0) return -1; // 0x40/0x80 label types are obsolete/reserved
            if (len > MAX_LABEL_LENGTH || pos + 1 + len > msg.length) return -1;
            wireLength += len + 1;
            if (wireLength + 1 > MAX_NAME_LENGTH) return -1;
            if (out != null) {
                if (out.length() > 0) out.append('.');
                for (int i = pos + 1; i <= pos + len; i++) {
                    int c = msg[i] & 0xFF;
                    // Keep the printable LDH set verbatim; escape everything else so
                    // distinct wire names never collapse to the same string key.
                    if (c > 0x20 && c < 0x7F && c != '.' && c != '\\') {
                        out.append((char) c);
                    } else {
                        out.append('\\').append(String.format(Locale.US, "%03d", c));
                    }
                }
            }
            pos += 1 + len;
        }
        return resumeAt >= 0 ? resumeAt : pos;
    }

    /** Skips a name without decoding it. Returns -1 when malformed. */
    public static int skipName(byte[] msg, int offset) {
        return readName(msg, offset, null);
    }

    /**
     * Builds an error response (NXDOMAIN, SERVFAIL, REFUSED, ...) that echoes the
     * query's ID, opcode, RD bit and first question, as required for the stub resolver
     * to accept it. Returns null if the query is too malformed to answer.
     */
    public static byte[] buildErrorResponse(byte[] query, int rcode) {
        if (query == null || query.length < HEADER_LENGTH) return null;
        Question q = parseQuestion(query);
        int questionEnd = q != null ? q.end : HEADER_LENGTH;
        byte[] resp = new byte[questionEnd];
        System.arraycopy(query, 0, resp, 0, questionEnd);
        int qflags = flags(query);
        int opcode = qflags & 0x7800;
        int rd = qflags & 0x0100;
        int cd = qflags & 0x0010;
        int respFlags = 0x8000 | opcode | rd | 0x0080 /* RA */ | cd | (rcode & 0x0F);
        put16(resp, 2, respFlags);
        put16(resp, 4, q != null ? 1 : 0);
        put16(resp, 6, 0);
        put16(resp, 8, 0);
        put16(resp, 10, 0);
        return resp;
    }

    /**
     * Validates that {@code response} is a well-formed answer to {@code query}:
     * QR set, matching ID, same opcode, and an identical first question
     * (case-insensitive, to tolerate 0x20 randomisation).
     */
    public static boolean isValidResponseFor(byte[] query, byte[] response) {
        if (query == null || response == null) return false;
        if (response.length < HEADER_LENGTH || query.length < HEADER_LENGTH) return false;
        if (!isResponse(response)) return false;
        if (id(query) != id(response)) return false;
        if ((flags(query) & 0x7800) != (flags(response) & 0x7800)) return false;
        Question q = parseQuestion(query);
        if (q == null) return false;
        if (qdCount(response) == 0) {
            // Some servers omit the question on FORMERR/REFUSED/NOTIMP; accept only errors.
            return rcode(response) != RCODE_NOERROR && rcode(response) != RCODE_NXDOMAIN;
        }
        Question r = parseQuestion(response);
        if (r == null) return false;
        if (!q.name.equals(r.name) || q.type != r.type || q.qclass != r.qclass) return false;
        // Every record header must be inside the buffer; catches truncated garbage.
        return walkRecords(response, null);
    }

    /**
     * How long a response may be cached, per RFC 1035 / RFC 2308:
     * positive answers use the minimum answer TTL; NXDOMAIN/NODATA use
     * min(SOA TTL, SOA MINIMUM) from the authority section; everything else
     * (SERVFAIL, REFUSED, truncated, malformed, no TTL information) is not cacheable.
     */
    public static long cacheTtlSeconds(byte[] response) {
        if (response == null || response.length < HEADER_LENGTH) return NOT_CACHEABLE;
        if (!isResponse(response) || isTruncated(response)) return NOT_CACHEABLE;
        int rcode = rcode(response);
        if (rcode != RCODE_NOERROR && rcode != RCODE_NXDOMAIN) return NOT_CACHEABLE;
        final long[] minAnswer = {Long.MAX_VALUE};
        final long[] negative = {Long.MAX_VALUE};
        boolean ok = walkRecords(response, (section, type, ttlOffset, rdataOffset, rdLength) -> {
            long ttl = u32(response, ttlOffset);
            if (section == 1 && type != TYPE_OPT) {
                minAnswer[0] = Math.min(minAnswer[0], ttl);
            } else if (section == 2 && type == TYPE_SOA) {
                long soaMinimum = soaMinimum(response, rdataOffset, rdLength);
                if (soaMinimum >= 0) negative[0] = Math.min(negative[0], Math.min(ttl, soaMinimum));
            }
        });
        if (!ok) return NOT_CACHEABLE;
        if (rcode == RCODE_NOERROR && anCount(response) > 0) {
            return minAnswer[0] == Long.MAX_VALUE ? NOT_CACHEABLE : minAnswer[0];
        }
        // NXDOMAIN or NODATA
        return negative[0] == Long.MAX_VALUE ? NOT_CACHEABLE : negative[0];
    }

    /**
     * Returns a copy of {@code response} with every non-OPT TTL reduced by
     * {@code elapsedSeconds} (floored at 0). Used when serving from cache so clients
     * never see TTLs that outlive the original expiry.
     */
    public static byte[] withTtlsReducedBy(byte[] response, long elapsedSeconds) {
        byte[] copy = response.clone();
        if (elapsedSeconds <= 0) return copy;
        walkRecords(copy, (section, type, ttlOffset, rdataOffset, rdLength) -> {
            if (type == TYPE_OPT) return;
            long ttl = u32(copy, ttlOffset);
            long reduced = Math.max(0L, ttl - elapsedSeconds);
            put32(copy, ttlOffset, reduced);
        });
        return copy;
    }

    /**
     * Removes the OPT pseudo-record from the additional section. Used when the
     * client did not send EDNS: RFC 6891 6.1.1 forbids an OPT in the reply then.
     * Returns the input unchanged if there is nothing to strip or it is malformed.
     */
    public static byte[] stripOpt(byte[] response) {
        if (response == null || response.length < HEADER_LENGTH || arCount(response) == 0) return response;
        final int[] optStart = {-1};
        final int[] optEnd = {-1};
        final int[] records = {0};
        int[] recordStart = new int[1];
        boolean ok = walkRecords(response, new RecordVisitor() {
            @Override
            public void visit(int section, int type, int ttlOffset, int rdataOffset, int rdLength) {
                if (section == 3 && type == TYPE_OPT && optStart[0] < 0) {
                    optStart[0] = recordStart[0];
                    optEnd[0] = rdataOffset + rdLength;
                }
                records[0]++;
            }

            @Override
            public void recordStart(int offset) {
                recordStart[0] = offset;
            }
        });
        if (!ok || optStart[0] < 0) return response;
        byte[] out = new byte[response.length - (optEnd[0] - optStart[0])];
        System.arraycopy(response, 0, out, 0, optStart[0]);
        System.arraycopy(response, optEnd[0], out, optStart[0], response.length - optEnd[0]);
        put16(out, 10, arCount(response) - 1);
        return out;
    }

    /** Visitor for resource records. section: 1=answer, 2=authority, 3=additional. */
    interface RecordVisitor {
        void visit(int section, int type, int ttlOffset, int rdataOffset, int rdLength);

        default void recordStart(int offset) {}
    }

    /** Walks all RRs after the question section. Returns false if malformed. */
    static boolean walkRecords(byte[] msg, RecordVisitor visitor) {
        if (msg.length < HEADER_LENGTH) return false;
        int pos = HEADER_LENGTH;
        int qd = qdCount(msg);
        for (int i = 0; i < qd; i++) {
            pos = skipName(msg, pos);
            if (pos < 0 || pos + 4 > msg.length) return false;
            pos += 4;
        }
        int[] counts = {anCount(msg), nsCount(msg), arCount(msg)};
        for (int s = 0; s < 3; s++) {
            for (int i = 0; i < counts[s]; i++) {
                int start = pos;
                pos = skipName(msg, pos);
                if (pos < 0 || pos + 10 > msg.length) return false;
                int type = u16(msg, pos);
                int ttlOffset = pos + 4;
                int rdLength = u16(msg, pos + 8);
                int rdataOffset = pos + 10;
                if (rdataOffset + rdLength > msg.length) return false;
                if (visitor != null) {
                    visitor.recordStart(start);
                    visitor.visit(s + 1, type, ttlOffset, rdataOffset, rdLength);
                }
                pos = rdataOffset + rdLength;
            }
        }
        return true;
    }

    private static long soaMinimum(byte[] msg, int rdataOffset, int rdLength) {
        int end = rdataOffset + rdLength;
        int pos = skipName(msg, rdataOffset); // MNAME
        if (pos < 0 || pos > end) return -1;
        pos = skipName(msg, pos);             // RNAME
        if (pos < 0 || pos + 20 > end) return -1;
        return u32(msg, pos + 16);            // SERIAL REFRESH RETRY EXPIRE MINIMUM
    }

    static int u16(byte[] b, int off) {
        if (b == null || off < 0 || off + 2 > b.length) return 0;
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    static long u32(byte[] b, int off) {
        return ((long) u16(b, off) << 16) | u16(b, off + 2);
    }

    static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }

    static void put32(byte[] b, int off, long v) {
        put16(b, off, (int) (v >> 16) & 0xFFFF);
        put16(b, off + 2, (int) v & 0xFFFF);
    }
}
