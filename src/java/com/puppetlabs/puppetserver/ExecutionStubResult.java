package com.puppetlabs.puppetserver;

public class ExecutionStubResult {
    private final String output;
    private final String error;
    private final int exitCode;

    public ExecutionStubResult(String output, String error, int exitCode) {
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
