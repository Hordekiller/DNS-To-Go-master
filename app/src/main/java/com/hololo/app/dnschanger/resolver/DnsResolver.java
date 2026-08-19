package com.hololo.app.dnschanger.resolver;

public interface DnsResolver {
    /**
     * Executes a DNS query and returns the raw response bytes.
     * @param rawQuery The raw DNS query message.
     * @return The raw DNS response message.
     * @throws Exception If any error occurs during resolution.
     */
    byte[] query(byte[] rawQuery) throws Exception;

    /**
     * Closes any resources associated with this resolver.
     */
    void close();

    /**
     * Returns true if this resolver has been closed and should not be reused.
     */
    default boolean isClosed() {
        return false;
    }
}
