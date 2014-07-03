package com.puppetlabs.master;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingPuppetProfiler implements PuppetProfiler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingPuppetProfiler.class);

    @Override
    public Object start(String message, String[] metric_id) {
        return System.currentTimeMillis();
    }

    @Override
    public void finish(Object context, String message, String[] metric_id) {
        String metric_id_str = StringUtils.join(metric_id, ' ');
        long elapsed_time = System.currentTimeMillis() - (Long)context;

        String msg = String.format("[%s] (%d ms) %s", metric_id_str, elapsed_time, message);
        LOGGER.debug(msg);
    }

    @Override
    public void shutdown() {
    }
}
