package com.puppetlabs.puppetserver;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
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

    private static final Pattern FUNCTION_PATTERN = Pattern.compile(".*\\.functions\\.([\\w\\d_]+)$");
    private static final Pattern RESOURCE_PATTERN = Pattern.compile(".*\\.compiler\\.evaluate_resource\\.([\\w\\d_]+\\[([\\w\\d_]+::)*[\\w\\d_]+\\])$");
    private static final Pattern CATALOG_PATTERN = Pattern.compile(".*\\.compiler\\.(static_compile_postprocessing|static_compile|compile|find_node)$");
    private static final Pattern INLINING_PATTERN = Pattern.compile(".*\\.compiler\\.static_compile_inlining\\.(.*)$");
    private static final Pattern PUPPETDB_PATTERN = Pattern.compile(".*\\.puppetdb\\.(resource\\.search|facts\\.encode|command\\.submit\\.replace facts|catalog\\.munge|command\\.submit\\.replace catalog|report\\.convert_to_wire_format_hash|command\\.submit\\.store report|query)$");


    public MetricsPuppetProfiler(String hostname, MetricRegistry registry) {
        this.hostname = hostname;
        this.registry = registry;
        this.metric_ids = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    }

    @Override
    public Object start(String message, String[] metric_id) {
        return System.currentTimeMillis();
    }

    @Override
    public void finish(Object context, String message, String[] metric_id) {
        if (shouldTime(metric_id)) {
          Long elapsed = System.currentTimeMillis() - (Long)context;
            for (Timer t : getTimers(metric_id)) {
                t.update(elapsed, TimeUnit.MILLISECONDS);
            }
        }
    }

    public Set<String> getAllMetricIds() {
        return this.metric_ids;
    }

    public Map<String, Timer> getFunctionTimers() {
        return getTimers(FUNCTION_PATTERN);
    }

    public Map<String, Timer> getResourceTimers() {
        return getTimers(RESOURCE_PATTERN);
    }

    public Map<String, Timer> getCatalogTimers() {
        return getTimers(CATALOG_PATTERN);
    }

    public Map<String, Timer> getInliningTimers() {
        return getTimers(INLINING_PATTERN);
    }

    public Map<String, Timer> getPuppetDBTimers() {
        return getTimers(PUPPETDB_PATTERN);
    }

    @Override
    public void shutdown() {
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

    private List<Timer> getTimers(String[] metric_id) {
        List<Timer> timers = new ArrayList<Timer>();
        // If this is turns out to be a performance hit, we could cache these in a
        // map or something.
        for (int i = 0; i < metric_id.length; i++) {
            List<String> current_id = new ArrayList<String>();
            for (int j = 0; j <= i; j++) {
                current_id.add(metric_id[j]);
            }
            String metric_name = getMetricName(current_id);
            registerMetricName(metric_name);
            timers.add(registry.timer(metric_name));
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

    private Map<String, Timer> getTimers(Pattern pattern) {
        Map<String, Timer> rv = new HashMap<>();
        for (String metric_id : this.metric_ids) {
            Matcher matcher = pattern.matcher(metric_id);
            if (matcher.matches()) {
                rv.put(matcher.group(1), registry.timer(metric_id));
            }
        }
        return rv;
    }
}
