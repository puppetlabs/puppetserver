package com.puppetlabs.puppetserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ExecutionStubImpl {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStubImpl.class);

    /**
     * Executes the given command in a separate process.
     *
     * @param command the command to execute
     * @return A 2-element array; the first element is the output of the process.
     *      The second element is the exit code of the process.
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public static ExecutionStubResult executeCommand(String command)
            throws InterruptedException, IOException {

        ProcessWrapper wrapper = new ProcessWrapper(Runtime.getRuntime().exec(command));

        String stdErr = wrapper.getErrorString();
        if ( ! stdErr.isEmpty() ) {
            log.warn("Executed an external process which logged to STDERR: " + stdErr);
        }

        return new ExecutionStubResult(wrapper.getOutputString(), wrapper.getExitCode());
    }
}
