package com.vortox.agent.chatproxy;

/** Raw HTTP status + body from a single {@link ChatRelay} call — deliberately unparsed so the
 *  caller (a web-framework adapter) decides how to forward it to its own browser-facing response. */
public record RelayResult(int statusCode, String body) {}
