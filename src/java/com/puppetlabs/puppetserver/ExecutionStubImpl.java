package com.puppetlabs.puppetserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ExecutionStubImpl {

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

        // This is adapted from
        // http://www.mkyong.com/java/how-to-execute-shell-command-from-java/

        StringBuilder output = new StringBuilder();

        Process p = Runtime.getRuntime().exec(command);
        p.waitFor();

        BufferedReader reader =
                new BufferedReader(new InputStreamReader(p.getInputStream()));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            return new ExecutionStubResult(output.toString(), p.exitValue());
        } finally {
            reader.close();
        }
    }
}
