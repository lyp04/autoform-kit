package com.autoformkit.app.report;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** One observed failure ready to be queued and uploaded. */
public final class FailureEvent {
    public final String stage;
    public final String errCode;
    public final String subphase;
    public final String message;
    public final String throwableText;
    public final LinkedHashMap<String, String> ctx;
    public final long timestampMs;
    public final String fingerprint;

    public FailureEvent(String stage, String errCode, String subphase, String message,
                        String throwableText, LinkedHashMap<String, String> ctx,
                        long timestampMs, String fingerprint) {
        this.stage = stage;
        this.errCode = errCode;
        this.subphase = subphase == null ? "" : subphase;
        this.message = message == null ? "" : message;
        this.throwableText = throwableText;
        this.ctx = ctx == null ? new LinkedHashMap<>() : ctx;
        this.timestampMs = timestampMs;
        this.fingerprint = fingerprint;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("stage", stage);
        json.put("errCode", errCode);
        json.put("subphase", subphase);
        json.put("message", message);
        if (throwableText != null) {
            json.put("throwable", throwableText);
        }
        json.put("ts", timestampMs);
        json.put("fp", fingerprint);
        JSONObject ctxJson = new JSONObject();
        for (Map.Entry<String, String> e : ctx.entrySet()) {
            ctxJson.put(e.getKey(), e.getValue());
        }
        json.put("ctx", ctxJson);
        return json;
    }

    public static FailureEvent fromJson(JSONObject json) throws JSONException {
        LinkedHashMap<String, String> ctx = new LinkedHashMap<>();
        JSONObject ctxJson = json.optJSONObject("ctx");
        if (ctxJson != null) {
            java.util.Iterator<String> it = ctxJson.keys();
            while (it.hasNext()) {
                String k = it.next();
                ctx.put(k, ctxJson.optString(k, ""));
            }
        }
        return new FailureEvent(
                json.optString("stage", ""),
                json.optString("errCode", ""),
                json.optString("subphase", ""),
                json.optString("message", ""),
                json.has("throwable") ? json.optString("throwable", null) : null,
                ctx,
                json.optLong("ts", System.currentTimeMillis()),
                json.optString("fp", ""));
    }
}
