package com.hololo.app.dnschanger.resolver;

import com.hololo.app.dnschanger.model.DnsServer;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DohResolver implements DnsResolver {
    private final String url;
    private final OkHttpClient client;
    private static final MediaType DNS_MESSAGE = MediaType.parse("application/dns-message");

    public DohResolver(DnsServer server, OkHttpClient client) {
        this.url = server.getDohUrl() != null ? server.getDohUrl() : "https://" + server.getHostname() + "/dns-query";
        this.client = client;
    }

    @Override
    public byte[] query(byte[] rawQuery) throws Exception {
        RequestBody body = RequestBody.create(rawQuery, DNS_MESSAGE);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Accept", "application/dns-message")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP error: " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }
            return responseBody.bytes();
        }
    }

    @Override
    public void close() {
        // OkHttpClient is shared, handled externally
    }
}
