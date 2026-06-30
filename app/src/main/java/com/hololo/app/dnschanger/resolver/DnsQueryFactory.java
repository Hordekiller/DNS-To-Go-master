package com.hololo.app.dnschanger.resolver;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class DnsQueryFactory {
    public static final int TYPE_A = 1;

    private DnsQueryFactory() {
    }

    public static byte[] buildAQuery(String domain) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int transactionId = (int) (System.nanoTime() & 0xFFFF);
        outputStream.write((transactionId >> 8) & 0xFF);
        outputStream.write(transactionId & 0xFF);
        outputStream.write(0x01);
        outputStream.write(0x00);
        outputStream.write(0x00);
        outputStream.write(0x01);
        outputStream.write(0x00);
        outputStream.write(0x00);
        outputStream.write(0x00);
        outputStream.write(0x00);
        outputStream.write(0x00);
        outputStream.write(0x00);

        String[] labels = domain.split("\\.");
        for (String label : labels) {
            byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
            outputStream.write(labelBytes.length & 0xFF);
            outputStream.write(labelBytes, 0, labelBytes.length);
        }
        outputStream.write(0x00);
        outputStream.write(0x00);
        outputStream.write(TYPE_A);
        outputStream.write(0x00);
        outputStream.write(0x01);
        return outputStream.toByteArray();
    }
}
