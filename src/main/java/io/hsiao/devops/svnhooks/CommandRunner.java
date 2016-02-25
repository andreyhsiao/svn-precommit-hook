package io.hsiao.devops.svnhooks;

import java.util.concurrent.FutureTask;

public class CommandRunner {
  public static int run(String command, String[] message) throws Exception {
    Process process = Runtime.getRuntime().exec(command);

    FutureTask<String> stdoutGlobber = new FutureTask<>(new StreamGobbler(process.getInputStream()));
    FutureTask<String> stderrGlobber = new FutureTask<>(new StreamGobbler(process.getErrorStream()));

    new Thread(stdoutGlobber).start();
    new Thread(stderrGlobber).start();

    String stdoutMessage = stdoutGlobber.get();
    String stderrMessage = stderrGlobber.get();

    int exitValue = process.waitFor();
    message[0] = (exitValue == 0) ? stdoutMessage : stderrMessage;

    return exitValue;
  }
}
