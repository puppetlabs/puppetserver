package com.puppetlabs.puppetserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class ExecutionStubImpl {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStubImpl.class);

    // This is adopted from
    // http://www.mkyong.com/java/how-to-execute-shell-command-from-java/

    private static String readInputStream(InputStream inputStream)
            throws IOException
    {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        final StringBuilder output = new StringBuilder();

        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        return output.toString();
    }

    private static String readStandardOut(Process p)
            throws IOException
    {
        return readInputStream(p.getInputStream());
    }

    private static String readStandardError(Process p)
            throws IOException
    {
        return readInputStream(p.getErrorStream());
    }

    public static String executeCommand(String command)
            throws InterruptedException, IOException
    {
        final Process p = Runtime.getRuntime().exec(command);
        p.waitFor();

        final String stdOut = readStandardOut(p);
        final String stdErr = readStandardError(p);

        if ( !stdErr.isEmpty() ) {
            if (stdOut.isEmpty() ) {
                throw new RuntimeException("ExecutionStub failure: " + stdErr);
            } else {
                log.warn(stdErr);
            }
        }

        // No error!
        return stdOut;
    }
}
