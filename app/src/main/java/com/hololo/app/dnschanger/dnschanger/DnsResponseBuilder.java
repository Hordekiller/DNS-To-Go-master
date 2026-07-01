package com.hololo.app.dnschanger.dnschanger;

import java.nio.ByteBuffer;

public final class DnsResponseBuilder {

    private static final int UDP_HEADER_LENGTH = 8;

    public static byte[] buildIpv4Response(byte[] dnsResponse, ByteBuffer originalPacket) {
        int ihl = (originalPacket.get(0) & 0x0F) * 4;
        byte[] srcIp = new byte[4];
        byte[] dstIp = new byte[4];
        originalPacket.position(12);
        originalPacket.get(srcIp);
        originalPacket.get(dstIp);

        int srcPort = originalPacket.getShort(ihl) & 0xFFFF;
        int dstPort = originalPacket.getShort(ihl + 2) & 0xFFFF;

        int totalLen = 20 + 8 + dnsResponse.length;
        ByteBuffer response = ByteBuffer.allocate(totalLen);

        response.put((byte) 0x45);
        response.put((byte) 0x00);
        response.putShort((short) totalLen);
        response.putShort((short) 0);
        response.putShort((short) 0x4000);
        response.put((byte) 64);
        response.put((byte) 17);
        int checksumPos = response.position();
        response.putShort((short) 0);
        response.put(dstIp);
        response.put(srcIp);

        short ipChecksum = calculateChecksum(response.array(), 20);
        response.putShort(checksumPos, ipChecksum);

        response.putShort((short) dstPort);
        response.putShort((short) srcPort);
        response.putShort((short) (8 + dnsResponse.length));
        response.putShort((short) 0);

        response.put(dnsResponse);
        return response.array();
    }

    public static byte[] buildIpv6Response(byte[] dnsResponse, ByteBuffer originalPacket) {
        byte[] srcIp = new byte[16];
        byte[] dstIp = new byte[16];
        originalPacket.position(8);
        originalPacket.get(srcIp);
        originalPacket.get(dstIp);

        int srcPort = originalPacket.getShort(40) & 0xFFFF;
        int dstPort = originalPacket.getShort(42) & 0xFFFF;

        int totalLen = 40 + 8 + dnsResponse.length;
        ByteBuffer response = ByteBuffer.allocate(totalLen);

        response.putInt(0x60000000);
        response.putShort((short) (8 + dnsResponse.length));
        response.put((byte) 17);
        response.put((byte) 64);
        response.put(dstIp);
        response.put(srcIp);

        response.putShort((short) dstPort);
        response.putShort((short) srcPort);
        response.putShort((short) (8 + dnsResponse.length));
        response.putShort((short) 0);

        int udpChecksum = calculateIPv6UdpChecksum(dstIp, srcIp, 8 + dnsResponse.length, response.array(), 40, dnsResponse);
        response.putShort(46, (short) udpChecksum);

        response.put(dnsResponse);
        return response.array();
    }

    public static byte[] buildNxDomainResponse(int transactionId, String domain) {
        ByteBuffer dnsResponse = ByteBuffer.allocate(12 + 64);
        dnsResponse.putShort((short) transactionId);
        dnsResponse.putShort((short) 0x8183);
        dnsResponse.putShort((short) 1);
        dnsResponse.putShort((short) 0);
        dnsResponse.putShort((short) 0);
        dnsResponse.putShort((short) 0);

        String[] labels = domain.split("\\.");
        for (String label : labels) {
            dnsResponse.put((byte) label.length());
            for (char c : label.toCharArray()) {
                dnsResponse.put((byte) c);
            }
        }
        dnsResponse.put((byte) 0);
        dnsResponse.putShort((short) 1);
        dnsResponse.putShort((short) 1);

        byte[] dnsData = new byte[dnsResponse.position()];
        dnsResponse.flip();
        dnsResponse.get(dnsData);
        return dnsData;
    }

    public static byte[] wrapInIpPacket(byte[] dnsResponse, ByteBuffer originalPacket) {
        int version = (originalPacket.get(0) >> 4) & 0x0F;
        if (version == 4) {
            return buildIpv4Response(dnsResponse, originalPacket);
        } else if (version == 6) {
            return buildIpv6Response(dnsResponse, originalPacket);
        }
        return null;
    }

    public static short calculateChecksum(byte[] data, int length) {
        int sum = 0;
        int i = 0;
        int remaining = length;
        while (remaining > 1) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            if ((sum & 0x80000000) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
            i += 2;
            remaining -= 2;
        }
        if (remaining > 0) {
            sum += (data[i] & 0xFF) << 8;
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (short) (~sum);
    }

    private static int calculateIPv6UdpChecksum(byte[] srcIp, byte[] dstIp, int udpLen,
                                                 byte[] udpHeader, int udpHeaderOffset, byte[] payload) {
        int sum = 0;

        for (int i = 0; i < 16; i += 2) {
            sum += ((srcIp[i] & 0xFF) << 8) | (srcIp[i + 1] & 0xFF);
            sum += ((dstIp[i] & 0xFF) << 8) | (dstIp[i + 1] & 0xFF);
        }
        sum += udpLen;
        sum += 17;

        for (int i = 0; i < 6; i += 2) {
            sum += ((udpHeader[udpHeaderOffset + i] & 0xFF) << 8) | (udpHeader[udpHeaderOffset + i + 1] & 0xFF);
        }

        int i = 0;
        int remaining = payload.length;
        while (remaining > 1) {
            sum += ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
            i += 2;
            remaining -= 2;
        }
        if (remaining > 0) {
            sum += (payload[i] & 0xFF) << 8;
        }

        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }
}
