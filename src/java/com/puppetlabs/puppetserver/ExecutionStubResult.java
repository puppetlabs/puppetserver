package com.puppetlabs.puppetserver;

public class ExecutionStubResult {
    private final String output;
    private final int exitCode;

    public ExecutionStubResult(String output, int exitCode) {
        this.output = output;
        this.exitCode = exitCode;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }
}
