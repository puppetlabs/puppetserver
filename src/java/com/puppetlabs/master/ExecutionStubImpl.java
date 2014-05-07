package com.puppetlabs.master;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ExecutionStubImpl {

    // This is copied from
    // http://www.mkyong.com/java/how-to-execute-shell-command-from-java/
    public static String executeCommand(String command)
            throws InterruptedException, IOException {

        StringBuilder output = new StringBuilder();

        Process p = Runtime.getRuntime().exec(command);
        p.waitFor();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(p.getInputStream()));

        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }

        return output.toString();
    }
}
