package org.lantern;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.lantern.event.Events;
import org.lantern.state.Model;
import org.lantern.state.SyncPath;
import org.lantern.util.LanternHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

public class StatsUpdater extends Thread {
    Logger log = LoggerFactory.getLogger(StatsUpdater.class);

    private final Model model;

    private final LanternHttpClient client;

    private static final long SLEEP_INTERVAL = 60 * 1000;

    @Inject
    public StatsUpdater(Model model, LanternHttpClient client) {
        super();
        setDaemon(true);
        this.model = model;
        this.client = client;
    }

    @Override
    public void run() {
        while (true) {
            updateStats();
            try {
                sleep(SLEEP_INTERVAL);
            } catch (InterruptedException e) {
                continue;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updateStats() {
        final HttpGet get = new HttpGet();
        try {
            final URI uri = new URI(LanternClientConstants.STATS_URL);
            get.setURI(uri);
            final HttpResponse response = client.execute(get);
            final HttpEntity entity = response.getEntity();
            final String json = IOUtils.toString(entity.getContent());
            EntityUtils.consume(entity);
            final ObjectMapper om = new ObjectMapper();
            Map<String, Object> stats = om.readValue(json, Map.class);
            Map<String, Object> global = (Map<String, Object>) stats.get("global");
            if (global == null) {
                log.info("Empty global stats");
                return;
            }
            updateModel(model.getGlobal(), global);
            Map<String, Object> countries = (Map<String, Object>) stats
                    .get("countries");
            for (Country country : model.getCountries().values()) {
                Object countryData = countries.get(country.getCode());
                if (countryData != null)
                    updateModel(country, (Map<String, Object>) countryData);
            }
        } catch (final IOException e) {
            log.warn("Could not connect to stats url", e);
        } catch (final URISyntaxException e) {
            log.error("URI error", e);
        } catch (IllegalAccessException e) {
            log.error("stats format error", e);
        } catch (InvocationTargetException e) {
            log.error("stats format error", e);
        } finally {
            get.reset();
        }
    }

    @SuppressWarnings("unchecked")
    private void updateModel(Object dest, Map<String, Object> src)
            throws IllegalAccessException, InvocationTargetException {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = src;
        try {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object value = entry.getValue(); // 5
                String key = entry.getKey(); // bps
                if (value instanceof Map) {
                    updateModel(PropertyUtils.getSimpleProperty(dest, key),
                            ((Map<String, Object>) value));
                } else {
                    PropertyUtils.setSimpleProperty(dest, key, value);
                }
            }
            Events.sync(SyncPath.GLOBAL, model.getGlobal());
            Events.sync(SyncPath.COUNTRIES, model.getCountries());
        } catch (NoSuchMethodException e) {
            // do nothing; lantern-controller collects more stats than lantern
            // uses
        }
    }

}
