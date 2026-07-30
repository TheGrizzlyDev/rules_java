// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.java.testrunner;

import com.google.devtools.build.runfiles.Runfiles;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class PersistentTestRunner {

  private static final int DEFAULT_CLASSPATH_LIMIT_UNIX = 120_000;
  private static final int DEFAULT_CLASSPATH_LIMIT_WINDOWS = 7_000;

  private final List<JvmProcess> spawnedJvms = new ArrayList<>();

  private PersistentTestRunner() {}

  public static void main(String[] args) throws Exception {
    Config config = null;
    List<String> forwarded = new ArrayList<>();
    boolean afterSeparator = false;
    for (String arg : args) {
      if (afterSeparator) {
        forwarded.add(arg);
      } else if ("--".equals(arg)) {
        afterSeparator = true;
      } else if (arg.startsWith("--config=")) {
        config = Config.parse(Paths.get(arg.substring("--config=".length())));
      } else {
        throw new IllegalArgumentException("unrecognized runner arg: " + arg);
      }
    }
    if (config == null) {
      throw new IllegalStateException("--config=<path> is required");
    }
    System.exit(new PersistentTestRunner().run(config, forwarded));
  }

  int run(Config config, List<String> testArgs) throws Exception {
    WrapperArgs wrapperArgs = WrapperArgs.parse(testArgs);
    if (wrapperArgs.printJavabin || "--print_javabin".equals(config.mainClass)) {
      System.out.print(config.javabin);
      return 0;
    }
    if (config.driverMain == null) {
      throw new IllegalStateException("driver_main is required in the config");
    }

    Runfiles runfiles = Runfiles.preload().unmapped();

    String javabin = resolveRunfile(runfiles, config.javabin);

    List<String> classpath = new ArrayList<>();
    for (String entry : config.driverClasspath) {
      classpath.add(resolveRunfile(runfiles, entry));
    }
    for (Config.ClasspathEntry entry : config.classpath) {
      classpath.add(resolveRunfile(runfiles, entry.path));
    }
    if (wrapperArgs.mainAdviceClasspath != null && !wrapperArgs.mainAdviceClasspath.isEmpty()) {
      classpath.add(0, wrapperArgs.mainAdviceClasspath);
    }
    String classpathString = String.join(File.pathSeparator, classpath);
    int limit = wrapperArgs.classpathLimit != null ? wrapperArgs.classpathLimit : classpathLimit();
    boolean useClasspathJar = classpathString.length() > limit;

    Path statusFile = Files.createTempFile("persistent-test-runner-status-", "");
    Files.delete(statusFile);

    List<String> command = new ArrayList<>();
    command.add(javabin);
    if (wrapperArgs.jvmDebugPort != null) {
      command.add(
          "-agentlib:jdwp=transport=dt_socket,server=y,suspend="
              + wrapperArgs.jvmDebugSuspend
              + ",address="
              + wrapperArgs.jvmDebugPort);
    }
    String testTmpdir = System.getenv("TEST_TMPDIR");
    if (testTmpdir != null && !testTmpdir.isEmpty() && Files.isDirectory(Paths.get(testTmpdir))) {
      command.add("-Djava.io.tmpdir=" + testTmpdir);
    }
    command.add("-Dbazel.persistent.test_main=" + config.mainClass);
    command.add("-Dbazel.persistent.status_file=" + statusFile);
    command.addAll(config.jvmFlags);
    command.addAll(wrapperArgs.jvmFlagsCmdline);
    command.add("-classpath");
    Path classpathJar = null;
    if (useClasspathJar) {
      classpathJar = writeClasspathJar(classpath);
      command.add(classpathJar.toString());
    } else {
      command.add(classpathString);
    }
    if (wrapperArgs.mainAdvice != null) {
      command.add(wrapperArgs.mainAdvice);
    }
    command.add(config.driverMain);
    command.addAll(wrapperArgs.programArgs);

    List<JvmProcess.ClasspathEntry> tracked = new ArrayList<>();
    for (int i = 0; i < config.classpath.size(); i++) {
      Config.ClasspathEntry entry = config.classpath.get(i);
      tracked.add(
          new JvmProcess.ClasspathEntry(
              entry.label,
              entry.path,
              sha256(classpath.get(i + config.driverClasspath.size()))));
    }

    List<String> mergedJvmFlags = new ArrayList<>(config.jvmFlags);
    mergedJvmFlags.addAll(wrapperArgs.jvmFlagsCmdline);

    ProcessBuilder pb = new ProcessBuilder(command).inheritIO();
    pb.environment().put("JACOCO_IS_JAR_WRAPPED", useClasspathJar ? "1" : "0");
    pb.environment().put("CLASSPATH_JAR", useClasspathJar ? classpathJar.getFileName().toString() : "");
    if (config.coverageMainClass != null) {
      pb.environment().put("JACOCO_MAIN_CLASS", config.coverageMainClass);
      String javaRunfiles = System.getenv("JAVA_RUNFILES");
      if (javaRunfiles != null) {
        pb.environment().put("JACOCO_JAVA_RUNFILES_ROOT", javaRunfiles + "/" + config.workspacePrefix);
      }
    }
    ensureUtf8Locale(pb);

    Process process = pb.start();
    JvmProcess tracker = new JvmProcess(process, javabin, mergedJvmFlags, tracked);
    spawnedJvms.add(tracker);
    logSpawnedJvm(tracker);

    int exitCode = awaitStatus(statusFile, process);

    if (classpathJar != null) {
      Files.deleteIfExists(classpathJar);
    }
    return exitCode;
  }

  private static int awaitStatus(Path statusFile, Process process) throws IOException, InterruptedException {
    while (true) {
      if (Files.exists(statusFile)) {
        String contents = new String(Files.readAllBytes(statusFile), StandardCharsets.UTF_8).trim();
        Files.deleteIfExists(statusFile);
        return Integer.parseInt(contents);
      }
      if (!process.isAlive()) {
        return process.exitValue();
      }
      Thread.sleep(50);
    }
  }

  private void logSpawnedJvm(JvmProcess p) {
    StringBuilder sb = new StringBuilder();
    sb.append("[persistent-test-runner] spawned JVM\n");
    sb.append("  javabin: ").append(p.javabin).append('\n');
    sb.append("  jvm_flags:\n");
    for (String flag : p.jvmFlags) {
      sb.append("    ").append(flag).append('\n');
    }
    sb.append("  classpath:\n");
    for (JvmProcess.ClasspathEntry entry : p.classpath) {
      sb.append("    ").append(entry.sha256).append("  ").append(entry.label);
      sb.append("  (").append(entry.path).append(")\n");
    }
    System.err.print(sb);
  }

  private static String sha256(String path) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] bytes = Files.readAllBytes(Paths.get(path));
      byte[] digest = md.digest(bytes);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (IOException | NoSuchAlgorithmException e) {
      return "unavailable";
    }
  }

  private static String resolveRunfile(Runfiles runfiles, String rlocation) {
    if (isAbsolute(rlocation)) {
      return rlocation;
    }
    String resolved = runfiles.rlocation(rlocation);
    return resolved != null ? resolved : rlocation;
  }

  private static boolean isAbsolute(String path) {
    if (path.startsWith("/")) {
      return true;
    }
    return path.length() > 2 && path.charAt(1) == ':';
  }

  private static boolean isWindows() {
    String os = System.getProperty("os.name", "").toLowerCase();
    return os.contains("win");
  }

  private static int classpathLimit() {
    return isWindows() ? DEFAULT_CLASSPATH_LIMIT_WINDOWS : DEFAULT_CLASSPATH_LIMIT_UNIX;
  }

  private static void ensureUtf8Locale(ProcessBuilder pb) {
    if (isWindows() || System.getProperty("os.name", "").toLowerCase().contains("mac")) {
      return;
    }
    java.util.Map<String, String> env = pb.environment();
    if (env.get("LC_ALL") == null && env.get("LC_CTYPE") == null && env.get("LANG") == null) {
      env.put("LC_CTYPE", "C.UTF-8");
    }
  }

  private static Path writeClasspathJar(List<String> classpath) throws IOException {
    Path jar = Files.createTempFile("classpath", ".jar");
    StringBuilder cp = new StringBuilder();
    for (int i = 0; i < classpath.size(); i++) {
      if (i > 0) {
        cp.append(' ');
      }
      String p = classpath.get(i);
      if (isWindows()) {
        cp.append("file:/").append(p.replace('\\', '/'));
      } else {
        cp.append("file:").append(p.startsWith("/") ? p : Paths.get("").toAbsolutePath().resolve(p));
      }
    }
    Manifest manifest = new Manifest();
    Attributes main = manifest.getMainAttributes();
    main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    main.putValue("Created-By", "Bazel");
    main.putValue("Class-Path", cp.toString());
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
    }
    return jar;
  }
}
