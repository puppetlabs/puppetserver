package com.puppetlabs.puppetserver;

import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.exec.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class ShellUtils {

    private static final Logger log = LoggerFactory.getLogger(ShellUtils.class);

    /**
     * Takes a prepared CommandLine instance and executes it using some sane
     * defaults and a DefaultExecutor. Also makes a delicious pancake.
     *
     * @param commandLine CommandLine instance to execute
     * @return An ExecutionResult with output[String], error[String], and
     *                  the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    private static ExecutionResult executeExecutor(CommandLine commandLine,
                                                   Map<String, String> env,
                                                   InputStream in)
            throws InterruptedException, IOException {
        DefaultExecutor executor = new DefaultExecutor();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler = new PumpStreamHandler(outStream, errStream, in);

        // Don't throw exception on non-zero exit code
        executor.setExitValues(null);

        // Set up the handlers
        executor.setStreamHandler(streamHandler);

        Integer exitCode = executor.execute(commandLine, env);

        String stdErr = errStream.toString();

        if ( ! stdErr.isEmpty() ) {
            log.warn("Executed an external process which logged to STDERR: " + stdErr);
        }

        return new ExecutionResult(outStream.toString(), stdErr, exitCode);
    }

    /**
     * Executes the given command in a separate process.
     *
     *
     * @param command the command [String] to execute. arguments can be
     *                included in the string.
     * @return An ExecutionResult with output[String], error[String], and
     *                the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public static ExecutionResult executeCommand(String command)
            throws InterruptedException, IOException {
        CommandLine commandLine = CommandLine.parse(command);

        return executeExecutor(commandLine, null, null);
    }

    /**
     * Executes the given command in a separate process.
     *
     * @param command the command [String] to execute.
     * @param arguments arguments [Array of Strings] to add to the command being executed
     * @return An ExecutionResult with output[String], error[String], and
     *                 the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public static ExecutionResult executeCommand(String command, String[] arguments)
            throws InterruptedException, IOException {
        return executeCommand(command, arguments, null, null);
    }

    /**
     * Executes the given command in a separate process.
     *
     * @param command the command [String] to execute.
     * @param arguments arguments [Array of Strings] to add to the command being executed
     * @param env environment variables [Map<String, String>] to expose to the command.
     *            If null, use system environment.
     * @param in optional stream to use as STDIN [InputStream]; may be null
     *
     * @return An ExecutionResult with output[String], error[String], and
     *                 the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */

    public static ExecutionResult executeCommand(String command, String[] arguments,
                                                 Map<String, String> env, InputStream in)
            throws InterruptedException, IOException {
        CommandLine commandLine = new CommandLine(command);
        commandLine.addArguments(arguments, false);

        return executeExecutor(commandLine, env, in);
    }
}
