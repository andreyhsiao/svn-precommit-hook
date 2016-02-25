package io.hsiao.devops.svnhooks;

public class Main {
  public static void main(String[] args) {
    new PreCommitHook(args).run();
  }
}
