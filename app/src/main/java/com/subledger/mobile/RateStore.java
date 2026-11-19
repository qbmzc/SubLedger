package com.subledger.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * Persists {@link Rates} in SharedPreferences and downloads rates from ExchangeRate-API's
 * open endpoint (no key, daily updates, attribution required in the UI).
 * Rates are device settings and are not part of the JSON backup.
 */
final class RateStore {
    static final String SOURCE = "ExchangeRate-API";
    static final String SOURCE_URL = "https://www.exchangerate-api.com";
    private static final String ENDPOINT = "https://open.er-api.com/v6/latest/";
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final AtomicBoolean FETCHING = new AtomicBoolean();
    private static final String PREFS = "rates";
    private static final String KEY = "state";
    private final SharedPreferences prefs;

    RateStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Rates load() {
        String json = prefs.getString(KEY, null);
        if (json != null) {
            try {
                JSONObject root = new JSONObject(json);
                return new Rates(root.getString("base"), readRates(root.getJSONObject("fetched")),
                        readRates(root.getJSONObject("manual")), root.getLong("fetchedAt"),
                        root.optLong("asOf"));
            } catch (JSONException | IllegalArgumentException ignored) {
                // Rates can be fetched again, so a corrupt entry falls back to defaults.
            }
        }
        return Rates.empty(Rates.defaultBase());
    }

    private void save(Rates rates) {
        try {
            JSONObject root = new JSONObject()
                    .put("base", rates.base)
                    .put("fetched", writeRates(rates.fetched))
                    .put("manual", writeRates(rates.manual))
                    .put("fetchedAt", rates.fetchedAt)
                    .put("asOf", rates.asOf);
            if (!prefs.edit().putString(KEY, root.toString()).commit()) {
                throw new IllegalStateException("汇率保存失败，请检查设备可用空间");
            }
        } catch (JSONException e) {
            throw new IllegalStateException("汇率保存失败", e);
        }
    }

    /** Applies a change to the stored rates atomically with respect to downloads. */
    Rates update(UnaryOperator<Rates> change) {
        synchronized (RateStore.class) {
            Rates next = change.apply(load());
            save(next);
            return next;
        }
    }

    /**
     * Downloads rates for the current base and stores them. Blocking; call off the main thread.
     * Returns false without doing anything when another download is already running.
     */
    boolean download() throws IOException, JSONException {
        if (!FETCHING.compareAndSet(false, true)) {
            return false;
        }
        try {
            String base = load().base;
            JSONObject root = new JSONObject(get(ENDPOINT + base));
            if (!"success".equals(root.optString("result")) || !base.equals(root.optString("base_code"))) {
                throw new IOException("汇率服务返回了无效数据");
            }
            // The API quotes "foreign units per 1 base"; store "base units per 1 foreign".
            Map<String, BigDecimal> rates = new TreeMap<>();
            JSONObject quoted = root.getJSONObject("rates");
            for (Iterator<String> keys = quoted.keys(); keys.hasNext(); ) {
                String code = keys.next();
                BigDecimal perBase;
                try {
                    perBase = new BigDecimal(quoted.get(code).toString());
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (perBase.signum() > 0) {
                    rates.put(code, BigDecimal.ONE.divide(perBase, MathContext.DECIMAL64));
                }
            }
            if (rates.isEmpty()) {
                throw new IOException("汇率服务没有返回汇率");
            }
            long asOf = root.optLong("time_last_update_unix") * 1000;
            // Ignore the result if the base changed while downloading.
            update(current -> current.base.equals(base)
                    ? current.withFetched(rates, System.currentTimeMillis(), asOf) : current);
            return true;
        } finally {
            FETCHING.set(false);
        }
    }

    private static String get(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("汇率服务暂时不可用（HTTP " + connection.getResponseCode() + "）");
            }
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IOException("汇率数据过大");
                    }
                    bytes.write(buffer, 0, count);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Map<String, BigDecimal> readRates(JSONObject object) throws JSONException {
        Map<String, BigDecimal> rates = new TreeMap<>();
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String code = keys.next();
            rates.put(code, new BigDecimal(object.getString(code)));
        }
        return rates;
    }

    private static JSONObject writeRates(Map<String, BigDecimal> rates) throws JSONException {
        JSONObject object = new JSONObject();
        for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
            object.put(entry.getKey(), entry.getValue().toPlainString());
        }
        return object;
    }
}
