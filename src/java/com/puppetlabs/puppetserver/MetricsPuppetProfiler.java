package com.puppetlabs.puppetserver;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetricsPuppetProfiler implements PuppetProfiler {

    private final String hostname;
    private final MetricRegistry registry;
    private final Set<String> metric_ids;

    private final Map<String, Timer> function_timers;
    private final Map<String, Timer> resource_timers;
    private final Map<String, Timer> catalog_timers;
    private final Map<String, Timer> inlining_timers;
    private final Map<String, Timer> puppetdb_timers;

    public MetricsPuppetProfiler(String hostname, MetricRegistry registry) {
        this.hostname = hostname;
        this.registry = registry;
        this.metric_ids = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        this.function_timers = new ConcurrentHashMap<String, Timer>();
        this.resource_timers = new ConcurrentHashMap<String, Timer>();
        this.catalog_timers = new ConcurrentHashMap<String, Timer>();
        this.inlining_timers = new ConcurrentHashMap<String, Timer>();
        this.puppetdb_timers = new ConcurrentHashMap<String, Timer>();
    }

    @Override
    public Object start(String message, String[] metric_id) {
        return System.currentTimeMillis();
    }

    @Override
    public void finish(Object context, String message, String[] metric_id) {
        if (shouldTime(metric_id)) {
          Long elapsed = System.currentTimeMillis() - (Long)context;
	    Map<String, Timer> metricsByID = getOrCreateTimersByIDs(metric_id);
            for (Timer t : metricsByID.values()) {
                t.update(elapsed, TimeUnit.MILLISECONDS);
            }

	    updateMetricsTrackers(metric_id, metricsByID);
        }
    }

    public Set<String> getAllMetricIds() {
        return this.metric_ids;
    }

    public Map<String, Timer> getFunctionTimers() {
        return this.function_timers;
    }

    public Map<String, Timer> getResourceTimers() {
        return this.resource_timers;
    }

    public Map<String, Timer> getCatalogTimers() {
        return this.catalog_timers;
    }

    public Map<String, Timer> getInliningTimers() {
        return this.inlining_timers;
    }

    public Map<String, Timer> getPuppetDBTimers() {
        return this.puppetdb_timers;
    }

    @Override
    public void shutdown() {
    }

    private List<String> sliceOfArrayToList(String[] idSegments, int lengthOfID) {
        // Callers expect a mutable List returned, but Arrays.asList() returns a
	// fix length array, which is why we have to create a List and then add to it.
        List<String> idList = new ArrayList<String>();
        idList.addAll(Arrays.asList(Arrays.copyOf(idSegments, lengthOfID)));

	return idList;
    }

    private String safeGet(String[] collection, int i) {
        try {
            return collection[i];
	}  catch (IndexOutOfBoundsException _ex) {
            return "";
	}
    }

    private void updateMetricsTrackers(String[] metricId, Map<String, Timer> metricsByID) {
	String firstElement = safeGet(metricId, 0);
	String secondElement = safeGet(metricId, 1);

        if ("functions".equals(firstElement)) {
            Timer metric = metricsByID.get(getMetricName(sliceOfArrayToList(metricId, 2)));
            this.function_timers.put(secondElement, metric);

        } else if ("compiler".equals(firstElement)) {
            String thirdElemet = safeGet(metricId, 2);

            if ("evaluate_resource".equals(secondElement)) {
                Timer metric = metricsByID.get(getMetricName(sliceOfArrayToList(metricId, 3)));
                this.resource_timers.put(thirdElemet, metric);

            } else if ("static_compile_inlining".equals(secondElement)) {
                Timer metric = metricsByID.get(getMetricName(sliceOfArrayToList(metricId, 3)));
                this.inlining_timers.put(thirdElemet, metric);

            } else {
                Timer metric = metricsByID.get(getMetricName(sliceOfArrayToList(metricId, 2)));
                this.catalog_timers.put(secondElement, metric);
            }

        } else if ("puppetdb".equals(firstElement)) {
            if ("query".equals(secondElement)) {
                Timer metric = metricsByID.get(getMetricName(sliceOfArrayToList(metricId, 2)));
                this.puppetdb_timers.put(secondElement, metric);

	    } else {
		String thirdElemet = safeGet(metricId, 2);

                if (
                    ("resource".equals(secondElement) && "search".equals(thirdElemet)) ||
		    ("payload".equals(secondElement) && "format".equals(thirdElemet)) ||
		    // Set.of would be preferrable but 7.x still support Java 8, which does not have Set.of
		    ("facts".equals(secondElement) && Arrays.asList("save", "find", "search", "encode").contains(thirdElemet)) ||
		    ("catalog".equals(secondElement) && Arrays.asList("save", "munge").contains(thirdElemet)) ||
		    ("report".equals(secondElement) && Arrays.asList("convert_to_wire_format_hash", "process").contains(thirdElemet))
		) {
                    String key = String.join(".", secondElement, thirdElemet);
                    Timer metric = metricsByID.get(getMetricName(sliceOfArrayToList(metricId, 3)));
                    this.puppetdb_timers.put(key, metric);

                } else if ("command".equals(secondElement) && "submit".equals(thirdElemet)) {
                    String fourthElement = safeGet(metricId, 3);

                    if (
                        "store report".equals(fourthElement) ||
			"replace facts".equals(fourthElement) ||
			"replace catalog".equals(fourthElement)
                    ) {
                        String key = String.join(".", secondElement, thirdElemet, fourthElement);
                        Timer metric = metricsByID.get(getMetricName(sliceOfArrayToList(metricId, 4)));
                        this.puppetdb_timers.put(key, metric);
		    }
		}
	    }
        }
    }

    private boolean shouldTime(String[] metric_id) {
        if (metric_id == null) {
            return false;
        }
        if ((metric_id.length > 0) && (metric_id[0].equals("http"))) {
            // Puppet contains some metrics that are intended to time http requests.  We have
            // more comprehensive metrics for this in our ring middleware, so we skip the
            // ones from Puppet.
            return false;
        }
        return true;
    }

    private Map<String, Timer> getOrCreateTimersByIDs(String[] metric_id) {
        Map<String, Timer> timers = new HashMap<String, Timer>();
        // If this is turns out to be a performance hit, we could cache these in a
        // map or something.
        for (int i = 0; i < metric_id.length; i++) {
            List<String> current_id = new ArrayList<String>();
            for (int j = 0; j <= i; j++) {
                current_id.add(metric_id[j]);
            }
            String metric_name = getMetricName(current_id);
            registerMetricName(metric_name);
            timers.put(metric_name, registry.timer(metric_name));
        }
        return timers;
    }

    private String getMetricName(List<String> metric_id) {
        metric_id.add(0, hostname);
        return MetricRegistry.name("puppetlabs", metric_id.toArray(new String[metric_id.size()]));
    }

    private void registerMetricName(String metric_name) {
        this.metric_ids.add(metric_name);
    }
}
