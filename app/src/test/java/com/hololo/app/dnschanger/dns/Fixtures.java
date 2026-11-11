package com.hololo.app.dnschanger.dns;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Hand-built DNS messages and IP packets for deterministic packet-level tests. */
public final class Fixtures {
    private Fixtures() {}

    public static final byte[] CLIENT_V4 = {10, 111, (byte) 222, 1};
    public static final byte[] VDNS_V4 = {10, 111, (byte) 222, 3};
    public static final byte[] CLIENT_V6 = v6("fd66f83ac65000000000000000000001");
    public static final byte[] VDNS_V6 = v6("fd66f83ac65000000000000000000003");

    private static byte[] v6(String hex) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    public static byte[] encodeName(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!name.isEmpty()) {
            for (String label : name.split("\\.")) {
                byte[] b = label.getBytes(StandardCharsets.US_ASCII);
                out.write(b.length);
                out.write(b, 0, b.length);
            }
        }
        out.write(0);
        return out.toByteArray();
    }

    public static byte[] query(int id, String name, int type) {
        return query(id, name, type, false);
    }

    public static byte[] query(int id, String name, int type, boolean withEdns) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put16(out, id);
        put16(out, 0x0100); // RD
        put16(out, 1);
        put16(out, 0);
        put16(out, 0);
        put16(out, withEdns ? 1 : 0);
        byte[] n = encodeName(name);
        out.write(n, 0, n.length);
        put16(out, type);
        put16(out, 1);
        if (withEdns) writeOpt(out);
        return out.toByteArray();
    }

    private static void writeOpt(ByteArrayOutputStream out) {
        out.write(0);
        put16(out, 41);
        put16(out, 1232);
        put32(out, 0);
        put16(out, 0);
    }

    /** NOERROR answer with one A record per TTL (rdata 1.2.3.4) using a compression pointer. */
    public static byte[] answer(byte[] query, long... ttls) {
        return response(query, 0, ttls, -1, -1, false);
    }

    /** NXDOMAIN with an SOA in the authority section. */
    public static byte[] nxdomain(byte[] query, long soaTtl, long soaMinimum) {
        return response(query, 3, new long[0], soaTtl, soaMinimum, false);
    }

    public static byte[] response(byte[] query, int rcode, long[] answerTtls, long soaTtl,
                                  long soaMinimum, boolean withOpt) {
        DnsMessage.Question q = DnsMessage.parseQuestion(query);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put16(out, DnsMessage.id(query));
        put16(out, 0x8180 | rcode);
        put16(out, 1);
        put16(out, answerTtls.length);
        put16(out, soaTtl >= 0 ? 1 : 0);
        put16(out, withOpt ? 1 : 0);
        out.write(query, 12, q.end - 12);
        for (long ttl : answerTtls) {
            put16(out, 0xC00C);
            put16(out, q.type);
            put16(out, 1);
            put32(out, ttl);
            put16(out, 4);
            out.write(new byte[]{1, 2, 3, 4}, 0, 4);
        }
        if (soaTtl >= 0) {
            put16(out, 0xC00C);
            put16(out, 6);
            put16(out, 1);
            put32(out, soaTtl);
            byte[] mname = encodeName("ns.example");
            byte[] rname = encodeName("hostmaster.example");
            put16(out, mname.length + rname.length + 20);
            out.write(mname, 0, mname.length);
            out.write(rname, 0, rname.length);
            put32(out, 1);
            put32(out, 7200);
            put32(out, 900);
            put32(out, 1209600);
            put32(out, soaMinimum);
        }
        if (withOpt) writeOpt(out);
        return out.toByteArray();
    }

    public static byte[] ipv4Udp(byte[] src, byte[] dst, int sport, int dport, byte[] payload) {
        int total = 20 + 8 + payload.length;
        ByteBuffer b = ByteBuffer.allocate(total);
        b.put((byte) 0x45).put((byte) 0).putShort((short) total).putShort((short) 0x1234)
                .putShort((short) 0x4000).put((byte) 64).put((byte) 17).putShort((short) 0)
                .put(src).put(dst);
        b.putShort((short) sport).putShort((short) dport).putShort((short) (8 + payload.length))
                .putShort((short) 0).put(payload);
        return b.array();
    }

    /**
     * IPv6 UDP packet. {@code extHeaders} is a list of next-header codes (0, 60, 43, 44)
     * inserted between the fixed header and UDP; generic ones are 8 or 16 bytes long.
     */
    public static byte[] ipv6Udp(byte[] src, byte[] dst, int sport, int dport, byte[] payload,
                                 int... extHeaders) {
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        for (int i = 0; i < extHeaders.length; i++) {
            int next = i + 1 < extHeaders.length ? extHeaders[i + 1] : 17;
            int code = extHeaders[i];
            if (code == 44) {
                ext.write(next);
                ext.write(0);
                put16(ext, 0); // offset 0, M=0: atomic fragment
                put32(ext, 0xCAFEBABEL);
            } else {
                int units = (i % 2 == 0) ? 0 : 1; // vary length: 8 or 16 bytes
                ext.write(next);
                ext.write(units);
                for (int k = 0; k < (units + 1) * 8 - 2; k++) ext.write(k == 0 ? 1 : 0); // PadN-ish
            }
        }
        byte[] extBytes = ext.toByteArray();
        int payloadLength = extBytes.length + 8 + payload.length;
        ByteBuffer b = ByteBuffer.allocate(40 + payloadLength);
        b.putInt(0x60000000).putShort((short) payloadLength)
                .put((byte) (extHeaders.length > 0 ? extHeaders[0] : 17)).put((byte) 64)
                .put(src).put(dst).put(extBytes);
        b.putShort((short) sport).putShort((short) dport).putShort((short) (8 + payload.length))
                .putShort((short) 0).put(payload);
        return b.array();
    }

    public static byte[] ipv4Tcp(byte[] src, byte[] dst, int sport, int dport, long seq, long ack, int flags) {
        int total = 40;
        ByteBuffer b = ByteBuffer.allocate(total);
        b.put((byte) 0x45).put((byte) 0).putShort((short) total).putShort((short) 0)
                .putShort((short) 0x4000).put((byte) 64).put((byte) 6).putShort((short) 0)
                .put(src).put(dst);
        b.putShort((short) sport).putShort((short) dport).putInt((int) seq).putInt((int) ack)
                .put((byte) 0x50).put((byte) flags).putShort((short) 65535).putShort((short) 0).putShort((short) 0);
        return b.array();
    }

    static void put16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    static void put32(ByteArrayOutputStream out, long v) {
        put16(out, (int) (v >>> 16) & 0xFFFF);
        put16(out, (int) v & 0xFFFF);
    }
}
