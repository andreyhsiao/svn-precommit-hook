package io.hsiao.devops.svnhooks;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.Callable;

public class StreamGobbler implements Callable<String> {
  private static final String ENCODING = "UTF-8";

  private InputStream ins;
  private String charset;

  public StreamGobbler(InputStream ins, String charset) {
    this.ins = ins;
    this.charset = charset;
  }

  public StreamGobbler(InputStream ins) {
    this(ins, ENCODING);
  }

  @Override
  public String call() throws Exception {
    StringBuilder sb = new StringBuilder();

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(ins, charset))) {
      String line = null;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
    }

    return sb.toString().trim();
  }
}
