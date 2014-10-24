package com.puppetlabs.puppetserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public class ProcessWrapper {

    private final StringWriter outputString;
    private final StringWriter errorString;
    private final int exitCode;

    private static final Logger log = LoggerFactory.getLogger(ProcessWrapper.class);

    public ProcessWrapper(Process process) throws InterruptedException
    {
        outputString = new StringWriter();
        errorString = new StringWriter();
        StreamBoozer seInfo = new StreamBoozer(process.getInputStream(), new PrintWriter(outputString, true));
        StreamBoozer seError = new StreamBoozer(process.getErrorStream(), new PrintWriter(errorString, true));
        seInfo.start();
        seError.start();
        exitCode = process.waitFor();
        seInfo.join();
        seError.join();
    }

    public String getErrorString() {
        return errorString.toString();
    }

    public String getOutputString() {
        return outputString.toString();
    }

    public int getExitCode() {
        return exitCode;
    }


    class StreamBoozer extends Thread {
        private final InputStream in;
        private final PrintWriter pw;

        StreamBoozer(InputStream in, PrintWriter pw) {
            this.in = in;
            this.pw = pw;
        }

        @Override
        public void run() {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(in));
                String line;
                while ((line = reader.readLine()) != null) {
                    pw.println(line);
                }
            } catch (IOException e) {
                log.error("Failed to read stream", e);
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                } catch (IOException e) {
                    log.warn("Attempt to close stream failed", e);
                }
            }
        }
    }
}