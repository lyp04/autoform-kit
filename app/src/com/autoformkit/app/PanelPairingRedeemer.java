package com.autoformkit.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTPS-only client for the versioned, one-time Panel pairing exchange.
 *
 * <p>The request contains only the opaque short-lived ticket. It deliberately sends no existing
 * Panel key, account, device identifier, form value, or deployment metadata. Redirects are never
 * followed, and neither failures nor callbacks expose the ticket in an error string.
 */
final class PanelPairingRedeemer {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int TOTAL_TIMEOUT_MS = 25_000;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final int MAX_ACCESS_KEY_LENGTH = 4096;
    // Strict two-field JSON object. Bearer credentials use RFC 6750's b64token alphabet, so no
    // JSON escaping is needed and duplicate/trailing fields cannot hide behind lenient parsers.
    private static final String KEY_CAPTURE = "([A-Za-z0-9._~+/-]+={0,2})";
    private static final Pattern VERSION_FIRST_RESPONSE = Pattern.compile(
        "\\A[ \\t\\r\\n]*\\{[ \\t\\r\\n]*\"version\"[ \\t\\r\\n]*"
            + ":[ \\t\\r\\n]*1[ \\t\\r\\n]*,[ \\t\\r\\n]*\"accessKey\""
            + "[ \\t\\r\\n]*:[ \\t\\r\\n]*\"" + KEY_CAPTURE
            + "\"[ \\t\\r\\n]*}[ \\t\\r\\n]*\\z");
    private static final Pattern KEY_FIRST_RESPONSE = Pattern.compile(
        "\\A[ \\t\\r\\n]*\\{[ \\t\\r\\n]*\"accessKey\"[ \\t\\r\\n]*"
            + ":[ \\t\\r\\n]*\"" + KEY_CAPTURE + "\"[ \\t\\r\\n]*,"
            + "[ \\t\\r\\n]*\"version\"[ \\t\\r\\n]*:[ \\t\\r\\n]*1"
            + "[ \\t\\r\\n]*}[ \\t\\r\\n]*\\z");
    private static final ScheduledExecutorService WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "panel-pairing-watchdog");
            thread.setDaemon(true);
            return thread;
        });

    enum Error {
        EXPIRED,
        NETWORK,
        REDIRECT,
        HTTP_STATUS,
        CONTENT_TYPE,
        RESPONSE_TOO_LARGE,
        INVALID_RESPONSE
    }

    static final class Result {
        final String accessKey;
        final Error error;

        private Result(String accessKey, Error error) {
            this.accessKey = accessKey;
            this.error = error;
        }

        static Result success(String accessKey) {
            return new Result(accessKey, null);
        }

        static Result failure(Error error) {
            return new Result("", error);
        }

        boolean succeeded() {
            return error == null && !accessKey.isEmpty();
        }
    }

    interface Callback {
        /** Invoked at most once on a background thread; cancellation suppresses pending delivery. */
        void onResult(Result result);
    }

    static final class Attempt {
        private enum State { ACTIVE, CANCELLED, DELIVERED }

        private final AtomicReference<Callback> callback;
        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
        private final AtomicReference<HttpsURLConnection> connection = new AtomicReference<>();
        private volatile Thread worker;
        private volatile ScheduledFuture<?> watchdog;

        private Attempt(Callback callback) {
            this.callback = new AtomicReference<>(callback);
        }

        void cancel() {
            state.compareAndSet(State.ACTIVE, State.CANCELLED);
            callback.set(null);
            abortConnection();
            ScheduledFuture<?> scheduled = watchdog;
            if (scheduled != null) scheduled.cancel(false);
        }

        private void timeout() {
            if (!state.compareAndSet(State.ACTIVE, State.DELIVERED)) return;
            deliverClaimed(Result.failure(Error.NETWORK));
            // Delivery is independent of whether a platform DNS/TLS/native call notices abort.
            // The worker's later result is suppressed by state, then this releases resources.
            abortConnection();
        }

        private boolean active() {
            return state.get() == State.ACTIVE;
        }

        private void abortConnection() {
            HttpsURLConnection active = connection.getAndSet(null);
            Thread activeWorker = worker;
            if (activeWorker != null) activeWorker.interrupt();
            if (active != null) {
                Thread cleanup = new Thread(() -> {
                    try {
                        active.disconnect();
                    } catch (Exception ignored) {
                    }
                }, "panel-pairing-disconnect");
                cleanup.setDaemon(true);
                cleanup.start();
            }
        }

        private void deliver(Result result) {
            if (!state.compareAndSet(State.ACTIVE, State.DELIVERED)) return;
            deliverClaimed(result);
        }

        private void deliverClaimed(Result result) {
            ScheduledFuture<?> scheduled = watchdog;
            if (scheduled != null) scheduled.cancel(false);
            Callback target = callback.getAndSet(null);
            if (target == null) return;
            try {
                target.onResult(result);
            } catch (Exception ignored) {
                // A UI callback bug must not leak the ticket or keep this worker alive.
            }
        }
    }

    private PanelPairingRedeemer() {}

    static Attempt redeem(PanelPairingLinkRules.Request request, Callback callback) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (callback == null) throw new IllegalArgumentException("callback is required");
        Attempt attempt = new Attempt(callback);
        Thread worker = new Thread(() -> {
            Result result;
            long now = System.currentTimeMillis() / 1000L;
            if (!attempt.active()) return;
            if (now - request.expiresAtEpochSeconds
                    > PanelPairingLinkRules.DEFAULT_CLOCK_SKEW_SECONDS) {
                result = Result.failure(Error.EXPIRED);
            } else {
                result = redeemBlocking(request, attempt);
            }
            attempt.deliver(result);
        }, "panel-pairing-redeem");
        attempt.worker = worker;
        attempt.watchdog = WATCHDOG.schedule(
            attempt::timeout, TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        worker.start();
        return attempt;
    }

    private static Result redeemBlocking(
            PanelPairingLinkRules.Request request, Attempt attempt) {
        HttpsURLConnection connection = null;
        try {
            URL endpoint = new URL(request.redeemUrl());
            if (!"https".equalsIgnoreCase(endpoint.getProtocol())) {
                return Result.failure(Error.NETWORK);
            }
            connection = (HttpsURLConnection) endpoint.openConnection();
            attempt.connection.set(connection);
            if (!attempt.active()) {
                return Result.failure(Error.NETWORK);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Cache-Control", "no-store");
            byte[] body = ("{\"version\":1,\"ticket\":\"" + request.ticket + "\"}")
                .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                return Result.failure(Error.REDIRECT);
            }
            if (status != 200) {
                return Result.failure(Error.HTTP_STATUS);
            }
            String contentType = connection.getHeaderField("Content-Type");
            if (!isJsonContentType(contentType)) {
                return Result.failure(Error.CONTENT_TYPE);
            }
            byte[] response;
            try (InputStream input = connection.getInputStream()) {
                response = readBounded(input);
            } catch (ResponseTooLargeException tooLarge) {
                return Result.failure(Error.RESPONSE_TOO_LARGE);
            }
            return parseSuccessBody(response);
        } catch (Exception ignored) {
            return Result.failure(Error.NETWORK);
        } finally {
            // Exactly one path owns disconnect: the worker when it can still claim the reference,
            // otherwise timeout/cancel already handed it to the daemon cleanup thread.
            if (connection != null && attempt.connection.compareAndSet(connection, null)) {
                connection.disconnect();
            }
        }
    }

    static Result parseHttpResponseForTest(int status, String contentType, byte[] body) {
        if (status >= 300 && status < 400) return Result.failure(Error.REDIRECT);
        if (status != 200) return Result.failure(Error.HTTP_STATUS);
        if (!isJsonContentType(contentType)) return Result.failure(Error.CONTENT_TYPE);
        if (body == null || body.length > MAX_RESPONSE_BYTES) {
            return Result.failure(body == null ? Error.INVALID_RESPONSE : Error.RESPONSE_TOO_LARGE);
        }
        return parseSuccessBody(body);
    }

    private static boolean isJsonContentType(String value) {
        if (value == null) return false;
        int separator = value.indexOf(';');
        String mediaType = (separator < 0 ? value : value.substring(0, separator)).trim();
        return "application/json".equalsIgnoreCase(mediaType);
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            if (output.size() + count > MAX_RESPONSE_BYTES) {
                throw new ResponseTooLargeException();
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Result parseSuccessBody(byte[] body) {
        try {
            String text = strictUtf8(body);
            Matcher match = VERSION_FIRST_RESPONSE.matcher(text);
            if (!match.matches()) {
                match = KEY_FIRST_RESPONSE.matcher(text);
                if (!match.matches()) return Result.failure(Error.INVALID_RESPONSE);
            }
            String key = match.group(1);
            if (key.isEmpty() || key.length() > MAX_ACCESS_KEY_LENGTH) {
                return Result.failure(Error.INVALID_RESPONSE);
            }
            return Result.success(key);
        } catch (Exception invalid) {
            return Result.failure(Error.INVALID_RESPONSE);
        }
    }

    private static String strictUtf8(byte[] value) throws CharacterCodingException {
        ByteBuffer input = ByteBuffer.wrap(value == null ? new byte[0] : value);
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(input);
        return decoded.toString();
    }

    private static final class ResponseTooLargeException extends Exception {}
}
