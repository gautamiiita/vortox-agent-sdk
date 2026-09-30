package com.vortox.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AnthropicClient#isTransient} — the classifier that decides whether a failed LLM call
 * is worth retrying.
 *
 * <p>It matches on the exception MESSAGE, not the type, which is why listing {@code "EOFException"}
 * did nothing for the error that actually occurs: {@code java.net.http} reports a response stream
 * closed early as {@code IOException: EOF reached while reading}. A run that had worked for seven
 * minutes was failed outright at iteration 30 by exactly that, with no retry attempted. These cases
 * pin the messages as they appear in the logs.
 */
class AnthropicClientTransientTest {

    @Test
    void treatsATruncatedResponseStreamAsTransient() {
        assertTrue(AnthropicClient.isTransient("Exception: EOF reached while reading"));
        assertTrue(AnthropicClient.isTransient("Exception: connection closed by peer"));
        assertTrue(AnthropicClient.isTransient("Exception: /2.0 GOAWAY received"));
    }

    @Test
    void keepsRetryingTheAlreadyKnownTransientClasses() {
        assertTrue(AnthropicClient.isTransient("Exception: request timed out"));
        assertTrue(AnthropicClient.isTransient("API error 529: overloaded_error"));
        assertTrue(AnthropicClient.isTransient("API error 429: rate_limit_error [retry-after=5s]"));
        assertTrue(AnthropicClient.isTransient("Exception: Connection reset"));
        assertTrue(AnthropicClient.isTransient("javax.net.ssl.SSLException: bad_record_mac"));
    }

    @Test
    void doesNotRetryWhatWillFailAgain() {
        // A permanent error must fail fast — retrying an invalid key or an oversized request three
        // times with backoff only delays the report by a minute.
        assertFalse(AnthropicClient.isTransient("API error 401: authentication_error"));
        assertFalse(AnthropicClient.isTransient("API error 400: prompt is too long"));
        assertFalse(AnthropicClient.isTransient("API key not configured"));
        assertFalse(AnthropicClient.isTransient(null));
    }

    /**
     * 2026-09-30: a DNS blip inside Docker. java.net.http throws a ConnectException with no message,
     * caused by an UnresolvedAddressException; "Exception: " + getMessage() made that
     * "Exception: null", which nothing recognised, so a whole run failed instead of retrying.
     */
    @Test
    void aFailedConnectionIsDescribedAndRetried() {
        Exception dns = new java.net.ConnectException();
        dns.initCause(new java.nio.channels.UnresolvedAddressException());
        String described = AnthropicClient.describe(dns);
        org.junit.jupiter.api.Assertions.assertEquals(
                "ConnectException (caused by UnresolvedAddressException)", described);
        assertTrue(AnthropicClient.isTransient("Exception: " + described));
        assertTrue(AnthropicClient.isTransient("Exception: " + AnthropicClient.describe(
                new java.net.UnknownHostException("api.anthropic.com"))));
        assertFalse(AnthropicClient.isTransient("Exception: " + AnthropicClient.describe(
                new IllegalArgumentException("bad model id"))));
    }

    @Test
    void describeKeepsMessagesAndStopsOnACycle() {
        Exception outer = new RuntimeException("outer", new IllegalStateException("inner"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "RuntimeException: outer (caused by IllegalStateException: inner)", AnthropicClient.describe(outer));
    }
}
