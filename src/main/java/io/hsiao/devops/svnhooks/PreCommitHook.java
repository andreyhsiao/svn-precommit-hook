package io.hsiao.devops.svnhooks;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PreCommitHook {
  private static final String PROPERTY_FILE_NAME = "hook.properties";
  private static final String SYS_ERROR_MESSAGE = "Sorry, it's not your fault, it's ours, please contact CM for assistance";

  private String repoPath;
  private String txnName;

  private Properties props;

  private String[] superUsers = {};
  private String[] statuses = {"Opened", "Reopened", "Active"};
  private String[] suffixes = {};

  private String author;
  private String message;
  private String[] changed;
  private String limit;

  private String artifactId;
  private String artifactStatus;
  private String fixedInRelease;
  private String fixedInReleaseStatus;
  private String packageDescription;

  private boolean dbdataCheck = true;
  private boolean namingCheck = true;
  private boolean logmsgCheck = false;

  public PreCommitHook(String[] args) {
    // parse command line arguments
    if (args.length < 2) {
      HookUtils.print(System.err, null, SYS_ERROR_MESSAGE, new Exception("bad arguments: both repository path and transaction name are mandatory"));
      System.exit(1);
    }

    repoPath = args[0];
    txnName = args[1];

    for (int idx = 2; idx < args.length; ++idx) {
      if (args[idx].startsWith("--superusers=")) {
        superUsers = HookUtils.getArgumentValue(args[idx]).split(",");
      }
      if (args[idx].startsWith("--allowable-statuses=")) {
        statuses = HookUtils.getArgumentValue(args[idx]).split(",");
      }
      if (args[idx].startsWith("--forbidden-suffixes=")) {
        suffixes = HookUtils.getArgumentValue(args[idx]).split(",");
      }
      if (args[idx].startsWith("--file-size-limit=")) {
        limit = HookUtils.getArgumentValue(args[idx]);
      }
      if (args[idx].equals("--no-check-db")) {
        dbdataCheck = false;
      }
      if (args[idx].equals("--no-check-naming")) {
        namingCheck = false;
      }
      if (args[idx].equals("--check-log-message")) {
        logmsgCheck = true;
      }
    }

    // load classpath properties
    try {
      props = HookUtils.loadProperties(getClass(), PROPERTY_FILE_NAME);
    }
    catch (Exception ex) {
      HookUtils.print(System.err, null, SYS_ERROR_MESSAGE, ex);
      System.exit(1);
    }
  }

  public void run() {
    try {
      fetchAuthor();
      if (isSuperUser()) {
        return;
      }

      fetchMessage();
      fetchArtifactId();
      fetchFromDBQuery();

      checkAccessStatus();
      checkArtifactStatus();
      checkFixedInRelease();
      checkRepository();

      fetchChanged();
      checkChanged();

      checkLogMessage();

      System.exit(0);
    }
    catch (Exception ex) {
      HookUtils.print(System.err, null, SYS_ERROR_MESSAGE, ex);
      System.exit(1);
    }
  }

  private boolean isSuperUser() {
    return Arrays.asList(superUsers).contains(author);
  }

  private void fetchAuthor() throws Exception {
    String[] output = new String[1];

    int exitValue = CommandRunner.run(String.format("svnlook author %s --transaction %s", repoPath, txnName), output);
    if (exitValue != 0) {
      throw new Exception(output[0]);
    }

    author = output[0].trim();
  }

  private void fetchMessage() throws Exception {
    String[] output = new String[1];

    int exitValue = CommandRunner.run(String.format("svnlook log %s --transaction %s", repoPath, txnName), output);
    if (exitValue != 0) {
      throw new Exception(output[0]);
    }

    message = output[0].trim();
  }

  private void fetchArtifactId() {
    Pattern pattern = Pattern.compile("\\A\\s*\\[\\s*(artf\\d+)\\s*\\]");
    Matcher matcher = pattern.matcher(message);

    if (!matcher.find()) {
      HookUtils.print(System.err, author, "Please provide artifact id in the commit message (eg: [artf12306])");
      System.exit(1);
    }

    artifactId = matcher.group(1).trim();
  }

  private void fetchFromDBQuery() throws Exception {
    String jdbcUrl = HookUtils.getProperty(props, "jdbc.url");
    String jdbcUsername = HookUtils.getProperty(props, "jdbc.username");
    String jdbcPassword = HookUtils.getProperty(props, "jdbc.password");

    try (Connection conn = DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword)) {
      PreparedStatement pstmt = conn.prepareStatement(getSQLPreparedStatement());
      pstmt.setString(1, artifactId);
      ResultSet rs = pstmt.executeQuery();

      if (!rs.next()) {
        StringBuilder sb = new StringBuilder();

        sb.append("Please make sure below requirements have been fulfilled:\n\n");
        sb.append("* artifact '" + artifactId + "' is valid and does exist\n" );
        sb.append("* artifact '" + artifactId + "' has 'Fixed in Release' field properly valued\n\n");
        sb.append("If all satisfied, we apology for the inconvenience and please contact CM for assistance");

        HookUtils.print(System.err, author, sb.toString());
        System.exit(1);
      }

      artifactStatus = rs.getString("artifact_status");
      fixedInRelease = rs.getString("fixed_in_release");
      fixedInReleaseStatus = rs.getString("fixed_in_release_status");
      packageDescription = rs.getString("package_description");
    }
  }

  private String getSQLPreparedStatement() {
    StringBuilder sb = new StringBuilder();

    sb.append("SELECT fv.value artifact_status, fr.status fixed_in_release_status, f1.title fixed_in_release, f2.description package_description\n");
    sb.append("FROM artifact a INNER JOIN field_value fv ON a.status_fv = fv.id\n");
    sb.append(" INNER JOIN relationship r ON r.target_id = a.id\n");
    sb.append(" INNER JOIN frs_release fr ON r.origin_id = fr.id\n");
    sb.append(" INNER JOIN folder f1 ON fr.id = f1.id\n");
    sb.append(" INNER JOIN folder f2 ON f2.id = f1.parent_folder_id\n");
    sb.append("WHERE a.id = ?\n");
    sb.append(" AND r.relationship_type_name = 'ArtifactResolvedRelease' AND r.is_deleted = '0'\n");
    sb.append(" AND fv.is_deleted = '0'\n");
    sb.append(" AND f1.is_deleted = '0'\n");
    sb.append(" AND f2.is_deleted = '0'\n");

    return sb.toString().trim();
  }

  private void checkAccessStatus() {
    if (packageDescription == null) {
      HookUtils.print(System.err, author, SYS_ERROR_MESSAGE, new Exception("invalid package description: empty"));
      System.exit(1);
    }

    Pattern pattern = Pattern.compile("\\[\\s*access\\s*:(.*?)\\]", Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(packageDescription);
    if (!matcher.find()) {
      return;
    }

    String[] accesses = matcher.group(1).trim().split(",");
    if (!Arrays.asList(accesses).contains(author)) {
      StringBuilder sb = new StringBuilder();

      sb.append("Sorry, you do not have permission to access the code branch\n\n");
      sb.append("If any questions, please contact CM for assistance");

      HookUtils.print(System.err, author, sb.toString());
      System.exit(1);
    }
  }

  private void checkArtifactStatus() {
    if (!Arrays.asList(statuses).contains(artifactStatus)) {
      StringBuilder sb = new StringBuilder();

      sb.append("Status '" + artifactStatus + "' is not allowed for code check-in\n\n");
      sb.append("Current allowed statuses are: " + Arrays.toString(statuses) + "\n\n");
      sb.append("Please do the needful and try again, or contacting CM for assistance");

      HookUtils.print(System.err, author, sb.toString());
      System.exit(1);
    }
  }

  private void checkFixedInRelease() {
    if (!fixedInReleaseStatus.equalsIgnoreCase("active")) {
      HookUtils.print(System.err, author, "Release version '" + fixedInRelease + "' is not in 'active' state, please contact CM for assistance");
      System.exit(1);
    }

    if (packageDescription == null) {
      HookUtils.print(System.err, author, SYS_ERROR_MESSAGE, new Exception("invalid package description: empty"));
      System.exit(1);
    }

    Pattern pattern = Pattern.compile("\\[\\s*version\\s*:(.*?)\\]", Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(packageDescription);
    if (!matcher.find()) {
      HookUtils.print(System.err, author, SYS_ERROR_MESSAGE, new Exception("invalid package description: release version missing"));
      System.exit(1);
    }

    String[] releases = matcher.group(1).trim().split(",");
    if (!Arrays.asList(releases).contains(fixedInRelease)) {
      StringBuilder sb = new StringBuilder();

      sb.append("Release version '" + fixedInRelease + "' is not allowed for code check-in\n\n");
      sb.append("Current allowed versions are: " + Arrays.toString(releases) + "\n\n");
      sb.append("Please do the needful and try again, or contacting CM for assistance");

      HookUtils.print(System.err, author, sb.toString());
      System.exit(1);
    }
  }

  private void checkRepository() {
    if (packageDescription == null) {
      HookUtils.print(System.err, author, SYS_ERROR_MESSAGE, new Exception("invalid package description: empty"));
      System.exit(1);
    }

    Pattern pattern = Pattern.compile("\\[\\s*repository\\s*:(.*?)\\]", Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(packageDescription);
    if (!matcher.find()) {
      return;
    }

    String repo1 = HookUtils.getBasename(repoPath).trim();
    String repo2 = matcher.group(1).trim();

    if (!repo1.equals(repo2)) {
      StringBuilder sb = new StringBuilder();

      sb.append("Repository '" + repo1 + "' is not allowed for code check-in\n\n");
      sb.append("Current allowed repository is: '" + repo2 + "'\n\n");
      sb.append("Please do the needful and try again, or contacting CM for assistance");

      HookUtils.print(System.err, author, sb.toString());
      System.exit(1);
    }
  }

  private void fetchChanged() throws Exception {
    String[] output = new String[1];

    int exitValue = CommandRunner.run(String.format("svnlook changed %s --transaction %s", repoPath, txnName), output);
    if (exitValue != 0) {
      throw new Exception(output[0]);
    }

    changed = output[0].trim().split("\\n");
  }

  private int fetchFileSize(String filePath) throws Exception {
    String[] output = new String[1];

    int exitValue = CommandRunner.run(String.format("svnlook --transaction %s filesize %s \"%s\"", txnName, repoPath, filePath), output);
    if (exitValue != 0) {
      throw new Exception(output[0]);
    }

    return Integer.valueOf(output[0].trim());
  }

  private void checkChanged() throws Exception {
    if (packageDescription == null) {
      HookUtils.print(System.err, author, SYS_ERROR_MESSAGE, new Exception("invalid package description: empty"));
      System.exit(1);
    }

    Pattern pattern = Pattern.compile("\\[\\s*branch\\s*:(.*?)\\]", Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(packageDescription);
    if (!matcher.find()) {
      HookUtils.print(System.err, author, SYS_ERROR_MESSAGE, new Exception("invalid package description: branch missing"));
      System.exit(1);
    }
    String[] branches = matcher.group(1).trim().split(",");

    List<String> namingViolatedItems = new ArrayList<>();
    List<String> slimitViolatedItems = new ArrayList<>();
    List<String> branchViolatedItems = new ArrayList<>();
    List<String> dbdataViolatedItems = new ArrayList<>();
    List<String> suffixViolatedItems = new ArrayList<>();

    for (String chgdItem: changed) {
      String type = chgdItem.trim().split("\\s+", 2)[0];
      String path = chgdItem.trim().split("\\s+", 2)[1];

      // naming violation check
      if (namingCheck && type.equalsIgnoreCase("A") && path.contains(" ")) {
        namingViolatedItems.add(path);
      }

      // size limit violation check
      if (limit != null && type.equalsIgnoreCase("A") && !path.endsWith("/")) {
        if (fetchFileSize(path) > HookUtils.getFileSizeLimit(limit)) {
          slimitViolatedItems.add(path);
        }
      }

      // branch violation check
      boolean branchViolated = true;
      for (String branch: branches) {
        if (path.startsWith(branch)) {
          branchViolated = false;
          break;
        }
      }

      if (branchViolated) {
        branchViolatedItems.add(path);
      }

      // suffix violation check
      boolean suffixViolated = false;
      for (String suffix: suffixes) {
        if (path.endsWith("." + suffix)) {
          suffixViolated = true;
          break;
        }
      }

      if (suffixViolated) {
        suffixViolatedItems.add(path);
      }

      // dbdata violation check
      if (dbdataCheck && (path.contains("/dbscript/") || path.contains("/demodata/"))) {
        if (path.endsWith("/")) {
          continue;
        }

        if (!path.contains("/src/main/dbscript/") && (path.matches(".*/dbscript/(?:initdb|initdata)/.*"))) {
          continue;
        }

        if (!path.contains("/" + fixedInRelease + "/")) {
          dbdataViolatedItems.add(path);
        }
      }
    }

    if (!namingViolatedItems.isEmpty()) {
      StringBuilder sb = new StringBuilder();

      sb.append("Below changes contain whitespaces in the naming, please check\n\n");
      for (String namingViolatedItem: namingViolatedItems) {
        sb.append(namingViolatedItem + "\n");
      }
      sb.append("\n");
      sb.append("Please do the needful and try again, or contacting CM for assistance");

      HookUtils.print(System.err, author, sb.toString());
      System.exit(1);
    }

    if (!slimitViolatedItems.isEmpty()) {
      StringBuilder sb = new StringBuilder();

      sb.append("Below changes exceeded maximum allowable size limit, please check\n\n");
      sb.append("Current allowed file size limit is: [" + limit + "]\n\n");
      for (String slimitViolatedItem: slimitViolatedItems) {
        sb.append(slimitViolatedItem + "\n");
      }
      sb.append("\n");
      sb.append("Please do the needful and try again, or contacting CM for assistance");

      HookUtils.print(System.err, author, sb.toString());
      System.exit(1);
    }

    if (!branchViolatedItems.isEmpty()) {
      StringBuilder sb = new StringBuilder();

      sb.append("Below changes are committing to the wrong branch, please check\n\n");
      for (String branchViolatedItem: branchViolatedItems) {
        sb.append(branchViolatedItem + "\n");
      }
      sb.append("\n");
      sb.append("Please do the needful and try again, or contacting CM for assistance");

      HookUtils.print(System.err, author, sb.toString());
      System.exit(1);
    }

    if (!suffixViolatedItems.isEmpty()) {
      StringBuilder sb = new StringBuilder();

      sb.append("Below changes are not allowed for check-in, please check\n\n");
      for (String suffixViolatedItem: suffixViolatedItems) {
        sb.append(suffixViolatedItem + "\n");
      }
      sb.append("\n");
      sb.append("Current forbidden file types are: " + Arrays.toString(suffixes) + "\n\n");
      sb.append("Please do the needful and try again, or contacting CM for assistance");

      HookUtils.print(System.err, author, sb.toString());
      System.exit(1);
    }

    if (!dbdataViolatedItems.isEmpty()) {
      StringBuilder sb = new StringBuilder();

      sb.append("Below changes are committing to the wrong db folder, please check\n\n");
      for (String dbdataViolatedItem: dbdataViolatedItems) {
        sb.append(dbdataViolatedItem + "\n");
      }
      sb.append("\n");
      sb.append("Please do the needful and try again, or contacting CM for assistance");

      HookUtils.print(System.err, author, sb.toString());
      System.exit(1);
    }
  }

  private void checkLogMessage() {
    if (!logmsgCheck) {
      return;
    }

    Pattern pattern = Pattern.compile("^\\s*what\\s*:\\s*\\S+.*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    Matcher matcher = pattern.matcher(message);
    if (!matcher.find()) {
      HookUtils.print(System.err, author, "Please provide 'What' information in the commit message");
      System.exit(1);
    }

    pattern = Pattern.compile("^\\s*reviewed\\s+by\\s*:\\s*\\S+.*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    matcher = pattern.matcher(message);
    if (!matcher.find()) {
      HookUtils.print(System.err, author, "Please provide 'Reviewed By' information in the commit message");
      System.exit(1);
    }
  }
}
