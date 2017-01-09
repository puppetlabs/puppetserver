package com.puppetlabs.puppetserver;

import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.output.TeeOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class ShellUtils {

    public static class ExecutionOptions {
        private boolean combineStdoutStderr = false;
        private Map<String, String> env = null;
        private InputStream stdin = null;

        public boolean getCombineStdoutStderr() {
            return combineStdoutStderr;
        }

        public void setCombineStdoutStderr(boolean combineStdoutStderr) {
            this.combineStdoutStderr = combineStdoutStderr;
        }

        public InputStream getStdin() {
            return stdin;
        }

        public void setStdin(InputStream stdin) {
            this.stdin = stdin;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ShellUtils.class);

    /**
     * Takes a prepared CommandLine instance and executes it using some sane
     * defaults and a DefaultExecutor. Also makes a delicious pancake.
     *
     * @param commandLine CommandLine instance to execute
     * @param options optional object [ExecutionOptions] to control behavior; may be null.
     * @return An ExecutionResult with output[String], error[String], and
     *                  the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    private static ExecutionResult executeExecutor(CommandLine commandLine,
                                                   ExecutionOptions options)
            throws InterruptedException, IOException {
        if (options == null) {
            options = new ExecutionOptions();
        }
        DefaultExecutor executor = new DefaultExecutor();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        // TODO the nice thing here would be to set up a piped stream
        // arrangement like:
        //    PipedOutputStream stdoutOutputStream = new PipedOutputStream();
        //    PipedInputStream stdoutInputStream = new PipedInputStream(stdoutOutputStream);
        // but this requires that the input stream be read on a different thread
        // than this one. this is currently out of scope.
        ByteArrayOutputStream stdoutOutputStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler;
        if (options.getCombineStdoutStderr()) {
            log.debug("Combining STDOUT/STDERR for external command '" + commandLine.toString() + "'");
            streamHandler = new PumpStreamHandler(
                    stdoutOutputStream,
                    new TeeOutputStream(stdoutOutputStream, errStream),
                    options.getStdin());
        } else {
            streamHandler = new PumpStreamHandler(stdoutOutputStream, errStream,
                    options.getStdin());
        }

        // Don't throw exception on non-zero exit code
        executor.setExitValues(null);

        // Set up the handlers
        executor.setStreamHandler(streamHandler);

        Integer exitCode = executor.execute(commandLine, options.getEnv());

        ByteArrayInputStream stdoutInputStream = new ByteArrayInputStream(stdoutOutputStream.toByteArray());
        String stdErr = errStream.toString();

        if ( ! stdErr.isEmpty() ) {
            log.warn("Executed an external process which logged to STDERR: " + stdErr);
        }

        return new ExecutionResult(stdoutInputStream, stdErr, exitCode);
    }

    /**
     * Executes the given command in a separate process.
     *
     * @param commandLine the command to execute.
     * @param options optional object [ExecutionOptions] to control behavior; may be null.
     *
     * @return An ExecutionResult with output[String], error[String], and
     *                 the exit code[Integer] of the process
     *
     * @throws InterruptedException
     */
    protected static ExecutionResult executeCommand(CommandLine commandLine,
                                                    ExecutionOptions options)
            throws InterruptedException {
        try {
            return executeExecutor(commandLine, options);
        } catch (IOException e) {
            // this nonsense is due to a weird edge-case incompatibility between JDK8
            // and apache commons-exec.  See SERVER-1116; hopefully we can remove this
            // conditional once that is resolved.
            if (e.getMessage() == "Stream closed") {
                log.warn("An error occurred while executing the command '" + commandLine.getExecutable() +
                        ".  The most likely culprit is that you are on JDK8, " +
                        "and we executed an external process with data on its STDIN that was not " +
                        "consumed by the process.  Please make sure the command above processes STDIN " +
                        "correctly.  For more information, see " +
                        "https://tickets.puppetlabs.com/browse/SERVER-1116 .  If you do not believe " +
                        "that this is the root cause of this error message, please file a bug at " +
                        "https://tickets.puppetlabs.com/browse/SERVER.");
            }
            throw new IllegalStateException(
                    "Exception while executing '" + commandLine.getExecutable() + "': " + e.getMessage(),
                    e);
        }
    }

    /**
     * Executes the given command in a separate process.
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

        return executeCommand(commandLine, null);
    }

    /**
     * Executes the given command in a separate process.
     *
     *
     * @param command the command [String] to execute. arguments can be
     *                included in the string.
     * @param options optional object [ExecutionOptions] to control behavior; may be null.
     * @return An ExecutionResult with output[String], error[String], and
     *                the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public static ExecutionResult executeCommand(String command, ExecutionOptions options)
            throws InterruptedException, IOException {
        CommandLine commandLine = CommandLine.parse(command);

        return executeCommand(commandLine, options);
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
        return executeCommand(command, arguments, null);
    }

    /**
     * Executes the given command in a separate process.
     *
     * @param command the command [String] to execute.
     * @param arguments arguments [Array of Strings] to add to the command being executed
     * @param options optional object [ExecutionOptions] to control behavior; may be null.
     *
     * @return An ExecutionResult with output[String], error[String], and
     *                 the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public static ExecutionResult executeCommand(String command, String[] arguments,
                                                 ExecutionOptions options)
            throws InterruptedException, IOException {
        CommandLine commandLine = new CommandLine(command);
        commandLine.addArguments(arguments, false);

        return executeCommand(commandLine, options);
    }


}
