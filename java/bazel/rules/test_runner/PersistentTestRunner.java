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

import com.google.devtools.build.java.testrunner.wire.Message;
import com.google.devtools.build.java.testrunner.wire.SessionReady;
import com.google.devtools.build.java.testrunner.wire.SessionStart;
import com.google.devtools.build.java.testrunner.wire.StoreGetRequest;
import com.google.devtools.build.java.testrunner.wire.StoreGetResponse;
import com.google.devtools.build.java.testrunner.wire.StoreSetAck;
import com.google.devtools.build.java.testrunner.wire.StoreSetRequest;
import com.google.devtools.build.java.testrunner.wire.TestFinished;
import com.google.devtools.build.java.testrunner.wire.WireChannel;
import com.google.devtools.build.java.testrunner.wire.WorkerShutdown;
import com.google.devtools.build.java.testrunner.wire.WorkerStart;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class PersistentTestRunner {

  private static final int DEFAULT_CLASSPATH_LIMIT_UNIX = 120_000;
  private static final int DEFAULT_CLASSPATH_LIMIT_WINDOWS = 7_000;
  private static final int MAX_JVMS = 4;

  private final JvmPool pool = new JvmPool(MAX_JVMS);
  private final CoordinatorStore store = new CoordinatorStore();

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
    String extensionsFilePath = null;
    if (config.extensionsFile != null && !config.extensionsFile.isEmpty()) {
      extensionsFilePath = resolveRunfile(runfiles, config.extensionsFile);
    }

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

    // TODO: add Windows support (needs named pipes instead of FIFOs).
    Path ipcDir = Files.createTempDirectory("persistent-test-runner-ipc-");
    Path c2j = ipcDir.resolve("c2j-" + UUID.randomUUID() + ".pipe");
    Path j2c = ipcDir.resolve("j2c-" + UUID.randomUUID() + ".pipe");
    mkfifo(c2j);
    mkfifo(j2c);

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
    if (extensionsFilePath != null) {
      command.add("-Dbazel.persistent.extensions_file=" + extensionsFilePath);
    }
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

    JvmProcess reusable = pool.tryReuse(tracked);
    if (reusable != null) {
      // TODO: dispatch this test into the reusable JVM instead of spawning a fresh one.
      // Requires an IPC channel to the driver process. For now, log, release it, and fall through.
      System.err.println(
          "[persistent-test-runner] would reuse an idle JVM with matching classpath (dispatch not yet implemented)");
      pool.release(reusable);
    }

    List<String> mergedJvmFlags = new ArrayList<>(config.jvmFlags);
    mergedJvmFlags.addAll(wrapperArgs.jvmFlagsCmdline);

    pool.reserveSlot();
    ProcessBuilder pb = new ProcessBuilder(command).inheritIO();
    pb.environment().put("BAZEL_PERSISTENT_IPC_C2J", c2j.toString());
    pb.environment().put("BAZEL_PERSISTENT_IPC_J2C", j2c.toString());
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
    pool.register(tracker);
    logSpawnedJvm(tracker);

    // Order matters: coordinator opens write side first (blocks until child opens the matching
    // read side), then the read side (blocks until child opens the matching write side). Child
    // does the mirror order. Explicit locals to avoid Java's arg-evaluation-order pitfall.
    WireChannel channel;
    try {
      java.io.OutputStream coordWrite = Files.newOutputStream(c2j);
      java.io.InputStream coordRead = Files.newInputStream(j2c);
      channel = new WireChannel(coordRead, coordWrite);
    } catch (IOException e) {
      process.destroyForcibly();
      throw e;
    }

    int exitCode;
    try {
      exitCode = runSession(channel, process);
    } finally {
      try {
        channel.close();
      } catch (IOException ignored) {
      }
      Files.deleteIfExists(c2j);
      Files.deleteIfExists(j2c);
      Files.deleteIfExists(ipcDir);
      pool.release(tracker);
    }

    if (classpathJar != null) {
      Files.deleteIfExists(classpathJar);
    }
    return exitCode;
  }

  private int runSession(WireChannel channel, Process process)
      throws IOException, InterruptedException {
    CompletableFuture<Integer> finished = new CompletableFuture<>();
    Thread reader =
        new Thread(
            () -> {
              try {
                while (true) {
                  Message msg = channel.read();
                  if (msg == null) {
                    // Child closed the wire without sending TestFinished. Wait for the
                    // process to exit so we can report its real exit code.
                    try {
                      process.waitFor();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    finished.complete(process.exitValue());
                    return;
                  }
                  handle(channel, msg, finished);
                  if (finished.isDone()) {
                    return;
                  }
                }
              } catch (IOException e) {
                finished.completeExceptionally(e);
              }
            },
            "persistent-test-runner-wire-reader");
    reader.setDaemon(true);
    reader.start();

    channel.send(SessionStart.INSTANCE);
    try {
      return finished.get();
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      throw new IOException(cause);
    }
  }

  private void handle(WireChannel channel, Message msg, CompletableFuture<Integer> finished)
      throws IOException {
    if (msg instanceof SessionReady) {
      // TODO: this coordinator only serves one child in its lifetime. When it becomes
      // long-lived, WorkerStart should be sent once per coordinator process, not once per
      // child JVM.
      channel.send(WorkerStart.INSTANCE);
      return;
    }
    if (msg instanceof StoreGetRequest) {
      StoreGetRequest r = (StoreGetRequest) msg;
      Optional<String> v = store.get(r.key());
      channel.send(new StoreGetResponse(r.requestId(), v.isPresent(), v.orElse("")));
      return;
    }
    if (msg instanceof StoreSetRequest) {
      StoreSetRequest r = (StoreSetRequest) msg;
      store.set(r.key(), r.value());
      channel.send(new StoreSetAck(r.requestId()));
      return;
    }
    if (msg instanceof TestFinished) {
      // TODO: when the coordinator is long-lived, WorkerShutdown belongs at coordinator
      // shutdown, not after a single TestFinished. For now these coincide.
      try {
        channel.send(WorkerShutdown.INSTANCE);
      } catch (IOException ignored) {
        // Child may have already died; we'll fall back to EOF handling.
      }
      finished.complete(((TestFinished) msg).exitCode());
      return;
    }
    throw new IOException("unexpected message: " + msg.getClass().getSimpleName());
  }

  private static void mkfifo(Path p) throws IOException, InterruptedException {
    Process mkfifo = new ProcessBuilder("mkfifo", p.toString()).inheritIO().start();
    if (mkfifo.waitFor() != 0) {
      throw new IOException("mkfifo failed for " + p);
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
