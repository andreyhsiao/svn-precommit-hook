package io.hsiao.devops.svnhooks;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Properties;

public class HookUtils {
  public static void print(PrintStream ps, String author, String message) {
    final String SYMBOL = "-";

    int length = Collections.max(Arrays.asList(message.split("\\n")), new Comparator<String>() {
      @Override
      public int compare(String str1, String str2) {
        return str1.length() > str2.length() ? 1:0;
      }
    }).length();

    ps.println(new String(new char[length]).replaceAll("\0", SYMBOL));
    if (author != null) {
      ps.println("Dear " + author + "\n");
    }
    ps.println(message);
    ps.println(new String(new char[length]).replaceAll("\0", SYMBOL));
  }

  public static void print(PrintStream ps, String author, String message, Throwable ex) {
    print(ps, author, message);
    ps.println();
    ex.printStackTrace(ps);
  }

  public static Properties loadProperties(Class<?> clazz, String file) throws Exception {
    Properties props = new Properties();

    try (InputStream ins = clazz.getResourceAsStream(file)) {
      if (ins == null) {
        throw new Exception("failed to locate property file '" + file + "'");
      }
      props.load(ins);
    }

    return props;
  }

  public static String getProperty(Properties props, String name) throws Exception {
    String value = props.getProperty(name, "");

    if (value.trim().isEmpty()) {
      throw new Exception("failed to get property '" + name + "'");
    }

    return value;
  }

  public static String getArgumentValue(String argument) {
    return argument.substring(argument.indexOf("=") + 1);
  }

  public static String getBasename(String path) {
    return Paths.get(path).getFileName().toString();
  }

  public static int getFileSizeLimit(String limit) {
    limit = limit.toUpperCase();

    if (limit.endsWith("K")) {
      return Integer.valueOf(limit.substring(0, limit.length() - 1)) * 1024;
    }
    else if (limit.endsWith("M")) {
      return Integer.valueOf(limit.substring(0, limit.length() - 1)) * 1024 * 1024;
    }
    else {
      return Integer.valueOf(limit);
    }
  }
}
