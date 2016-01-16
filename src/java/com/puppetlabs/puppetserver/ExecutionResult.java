package com.puppetlabs.puppetserver;

public class ExecutionResult {
    private final String output;
    private final String error;
    private final int exitCode;

    public ExecutionResult(String output, String error, int exitCode) {
        this.output = output;
        this.error = error;
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }
}
