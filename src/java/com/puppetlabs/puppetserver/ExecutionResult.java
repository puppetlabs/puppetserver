package com.puppetlabs.puppetserver;

import java.io.InputStream;
import java.io.IOException;
import org.apache.commons.io.IOUtils;

public class ExecutionResult {
    private final InputStream output;
    private final String error;
    private final int exitCode;

    public ExecutionResult(InputStream output, String error, int exitCode) {
        this.output = output;
        this.error = error;
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() throws IOException {
        return IOUtils.toString(output, "UTF-8");
    }

    public InputStream getOutputAsStream() {
        return output;
    }

    public String getError() {
        return error;
    }
}
