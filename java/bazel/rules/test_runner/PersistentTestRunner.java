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

import com.google.devtools.build.java.testrunner.persistent_worker.PersistentWorker;
import com.google.devtools.build.java.testrunner.wire.Message;
import com.google.devtools.build.java.testrunner.wire.SessionReady;
import com.google.devtools.build.java.testrunner.wire.SessionStart;
import com.google.devtools.build.java.testrunner.wire.StoreGetRequest;
import com.google.devtools.build.java.testrunner.wire.StoreGetResponse;
import com.google.devtools.build.java.testrunner.wire.StoreSetAck;
import com.google.devtools.build.java.testrunner.wire.StoreSetRequest;
import com.google.devtools.build.java.testrunner.wire.StoreValue;
import com.google.devtools.build.java.testrunner.wire.TestFinished;
import com.google.devtools.build.java.testrunner.wire.TestStart;
import com.google.devtools.build.java.testrunner.wire.WireChannel;
import com.google.devtools.build.java.testrunner.wire.WorkerShutdown;
import com.google.devtools.build.java.testrunner.wire.WorkerStart;
import com.google.devtools.build.runfiles.Runfiles;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class PersistentTestRunner {

  private static final int DEFAULT_CLASSPATH_LIMIT_UNIX = 120_000;
  private static final int DEFAULT_CLASSPATH_LIMIT_WINDOWS = 7_000;
  private static final int MAX_JVMS = 4;

  private final JvmPool pool = new JvmPool(MAX_JVMS);
  private final CoordinatorStore store = new CoordinatorStore();
  private final List<ReusableJvm> reusableChildren = new ArrayList<>();

  private PersistentTestRunner() {}

  public static void main(String[] args) throws Exception {
    PersistentTestRunner runner = new PersistentTestRunner();
    Runtime.getRuntime()
        .addShutdownHook(new Thread(runner::closeChildren, "persistent-test-runner-shutdown"));
    try {
      PersistentWorker.run(args, runner::handleRequest);
    } finally {
      runner.closeChildren();
    }
  }

  int handleRequest(List<String> args, PrintStream stdout, PrintStream stderr) throws Exception {
    return handleRequest(args, System.getenv(), stdout, stderr);
  }

  int handleRequest(
      List<String> args, Map<String, String> environment, PrintStream stdout, PrintStream stderr)
      throws Exception {
    Config config = null;
    List<String> forwarded = new ArrayList<>();
    boolean afterSeparator = false;
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (afterSeparator) {
        forwarded.add(arg);
      } else if ("--".equals(arg)) {
        afterSeparator = true;
      } else if (arg.startsWith("--config=") || "--config".equals(arg)) {
        String raw;
        if ("--config".equals(arg)) {
          if (++i == args.size()) {
            throw new IllegalArgumentException("--config requires a path");
          }
          raw = args.get(i);
        } else {
          raw = arg.substring("--config=".length());
        }
        // Two supported shapes:
        //   - one-shot mode (wrapper.sh runs): path is already absolute
        //     (${JAVA_RUNFILES}/…) — pass through.
        //   - worker mode (coordinator as PTR worker): path is exec-root
        //     relative (bazel-out/…); the coordinator's cwd is the exec root,
        //     so a plain Paths.get resolves correctly.
        // If neither works, fall back to runfiles rlocation for legacy paths.
        Path cfgPath = Paths.get(raw);
        if (!Files.exists(cfgPath)) {
          cfgPath = Paths.get(resolveRunfile(Runfiles.preload().unmapped(), raw));
        }
        config = Config.parse(cfgPath);
      } else {
        throw new IllegalArgumentException("unrecognized runner arg: " + arg);
      }
    }
    if (config == null) {
      throw new IllegalStateException("--config=<path> is required");
    }
    return run(config, forwarded, environment, stdout, stderr);
  }

  int run(Config config, List<String> testArgs, PrintStream stdout, PrintStream stderr)
      throws Exception {
    return run(config, testArgs, System.getenv(), stdout, stderr);
  }

  int run(
      Config config,
      List<String> testArgs,
      Map<String, String> environment,
      PrintStream stdout,
      PrintStream stderr)
      throws Exception {
    WrapperArgs wrapperArgs = WrapperArgs.parse(testArgs, environment);
    if (wrapperArgs.printJavabin || "--print_javabin".equals(config.mainClass)) {
      stdout.print(config.javabin);
      return 0;
    }
    if (config.driverMain == null) {
      throw new IllegalStateException("driver_main is required in the config");
    }

    environment =
        TestEnvironment.prepare(
            environment, config.execPaths ? config.execRoot : Paths.get("").toAbsolutePath());
    Runfiles runfiles = config.execPaths ? null : Runfiles.preload(environment).unmapped();

    String javabin = resolveConfigPath(config, runfiles, config.javabin);
    String extensionsFilePath = null;
    if (config.extensionsFile != null && !config.extensionsFile.isEmpty()) {
      extensionsFilePath = resolveConfigPath(config, runfiles, config.extensionsFile);
    }

    List<String> classpath = new ArrayList<>();
    for (String entry : config.driverClasspath) {
      classpath.add(resolveConfigPath(config, runfiles, entry));
    }
    for (Config.ClasspathEntry entry : config.classpath) {
      classpath.add(resolveConfigPath(config, runfiles, entry.path));
    }
    if (wrapperArgs.mainAdviceClasspath != null && !wrapperArgs.mainAdviceClasspath.isEmpty()) {
      classpath.add(0, wrapperArgs.mainAdviceClasspath);
    }
    if ((config.mainClass.equals("com.google.testing.junit.runner.BazelTestRunner")
            || (config.mainClass.equals("com.google.testing.coverage.JacocoCoverageRunner")
                && "com.google.testing.junit.runner.BazelTestRunner"
                    .equals(config.coverageMainClass)))
        && config.driverMain.equals("com.google.devtools.build.java.testrunner.JvmDriver")
        && System.getProperty("os.name").equals("Linux")
        && wrapperArgs.mainAdvice == null
        && wrapperArgs.mainAdviceClasspath == null
        && wrapperArgs.jvmDebugPort == null
        && !System.getProperty("bazel.persistent.reuse", "true").equals("false")) {
      return runReusable(
          config, wrapperArgs, environment, javabin, extensionsFilePath, classpath, stdout, stderr);
    }

    String classpathString = String.join(File.pathSeparator, classpath);
    int limit = wrapperArgs.classpathLimit != null ? wrapperArgs.classpathLimit : classpathLimit();
    boolean useClasspathJar = classpathString.length() > limit;

    // Allow slow startup to be configured without changing per-test JVM flags.
    long startupTimeoutSeconds =
        Long.parseLong(System.getProperty("bazel.persistent.startup_timeout_seconds", "30"));
    if (startupTimeoutSeconds <= 0 || startupTimeoutSeconds > Long.MAX_VALUE / 1000) {
      throw new IllegalArgumentException(
          "bazel.persistent.startup_timeout_seconds must be positive");
    }
    long startupTimeoutMillis = startupTimeoutSeconds * 1000;
    if (wrapperArgs.jvmDebugPort != null && "y".equals(wrapperArgs.jvmDebugSuspend)) {
      startupTimeoutMillis = 0; // Wait for debugger attachment, but still detect child exit.
    }

    long shutdownTimeoutSeconds =
        Long.parseLong(System.getProperty("bazel.persistent.shutdown_timeout_seconds", "30"));
    if (shutdownTimeoutSeconds <= 0 || shutdownTimeoutSeconds > Long.MAX_VALUE / 1_000_000_000) {
      throw new IllegalArgumentException(
          "bazel.persistent.shutdown_timeout_seconds must be positive"
              + " and representable in nanoseconds");
    }
    long shutdownTimeoutNanos = TimeUnit.SECONDS.toNanos(shutdownTimeoutSeconds);

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
    String testTmpdir = environment.get("TEST_TMPDIR");
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
              entry.label, entry.path, sha256(classpath.get(i + config.driverClasspath.size()))));
    }

    List<String> mergedJvmFlags = new ArrayList<>(config.jvmFlags);
    mergedJvmFlags.addAll(wrapperArgs.jvmFlagsCmdline);

    pool.reserveSlot();
    ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
    pb.environment().clear();
    pb.environment().putAll(environment);
    pb.environment().put("BAZEL_PERSISTENT_IPC_C2J", c2j.toString());
    pb.environment().put("BAZEL_PERSISTENT_IPC_J2C", j2c.toString());
    pb.environment().put("JACOCO_IS_JAR_WRAPPED", useClasspathJar ? "1" : "0");
    pb.environment()
        .put("CLASSPATH_JAR", useClasspathJar ? classpathJar.getFileName().toString() : "");
    if (config.coverageMainClass != null) {
      pb.environment().put("JACOCO_MAIN_CLASS", config.coverageMainClass);
      String javaRunfiles = environment.get("JAVA_RUNFILES");
      if (javaRunfiles != null) {
        pb.environment()
            .put("JACOCO_JAVA_RUNFILES_ROOT", javaRunfiles + "/" + config.workspacePrefix);
      }
    }
    ensureUtf8Locale(pb);

    long startupStarted = System.nanoTime();
    Process process = pb.start();
    Thread stdoutDrain = drain(process.getInputStream(), stdout, "child-stdout-drain");
    Thread stderrDrain = drain(process.getErrorStream(), stderr, "child-stderr-drain");
    JvmProcess tracker = new JvmProcess(process, javabin, mergedJvmFlags, tracked);
    pool.register(tracker);
    logSpawnedJvm(tracker, stderr);

    WireChannel channel;
    try {
      long connectionTimeout =
          startupTimeoutMillis == 0
              ? 0
              : Math.max(
                  1,
                  startupTimeoutMillis
                      - java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                          System.nanoTime() - startupStarted));
      channel = FifoConnection.open(process, c2j, j2c, connectionTimeout);
    } catch (IOException | InterruptedException e) {
      process.destroyForcibly();
      boolean interrupted = ReusableJvm.waitForExit(process, 1000);
      // Bound cleanup even if a descendant has inherited the child's output pipes.
      try {
        stdoutDrain.join(1000);
        stderrDrain.join(1000);
      } catch (InterruptedException cleanupInterrupted) {
        e.addSuppressed(cleanupInterrupted);
        interrupted = true;
      }
      Files.deleteIfExists(c2j);
      Files.deleteIfExists(j2c);
      Files.deleteIfExists(ipcDir);
      if (classpathJar != null) {
        Files.deleteIfExists(classpathJar);
      }
      pool.release(tracker);
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      if (e instanceof InterruptedException) {
        throw (InterruptedException) e;
      }
      throw new IOException(
          "Child startup failed: " + e.getMessage() + "\nCommand: " + String.join(" ", command), e);
    }

    try {
      Integer reportedExitCode = runSession(channel, process, startupStarted, startupTimeoutMillis);
      // TestFinished and wire EOF both start shutdown. Neither proves that the
      // process has exited: System.exit can still be running user shutdown hooks.
      long shutdownStarted = System.nanoTime();
      if (!process.waitFor(
              remainingNanos(shutdownStarted, shutdownTimeoutNanos), TimeUnit.NANOSECONDS)
          || !joinWithin(stdoutDrain, shutdownStarted, shutdownTimeoutNanos)
          || !joinWithin(stderrDrain, shutdownStarted, shutdownTimeoutNanos)) {
        throw new IOException(
            "Child shutdown timed out after "
                + shutdownTimeoutSeconds
                + " seconds waiting for process exit and output draining");
      }
      return reportedExitCode == null ? process.exitValue() : reportedExitCode;
    } finally {
      // Also bound cleanup on protocol errors, interruption, and inherited pipes.
      // Closing a buffered wire stream can wait on its blocked reader's monitor.
      process.destroyForcibly();
      Thread closeChannel =
          new Thread(
              () -> {
                try {
                  channel.close();
                } catch (IOException ignored) {
                }
              },
              "persistent-test-runner-wire-close");
      closeChannel.setDaemon(true);
      closeChannel.start();
      long cleanupStarted = System.nanoTime();
      long cleanupNanos = TimeUnit.SECONDS.toNanos(1);
      boolean interrupted = ReusableJvm.waitForExit(process, 1000);
      try {
        joinWithin(closeChannel, cleanupStarted, cleanupNanos);
        joinWithin(stdoutDrain, cleanupStarted, cleanupNanos);
        joinWithin(stderrDrain, cleanupStarted, cleanupNanos);
      } catch (InterruptedException e) {
        interrupted = true;
      }
      Files.deleteIfExists(c2j);
      Files.deleteIfExists(j2c);
      Files.deleteIfExists(ipcDir);
      pool.release(tracker);
      if (classpathJar != null) {
        Files.deleteIfExists(classpathJar);
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private int runReusable(
      Config config,
      WrapperArgs wrapperArgs,
      Map<String, String> environment,
      String javabin,
      String extensionsFile,
      List<String> classpath,
      PrintStream stdout,
      PrintStream stderr)
      throws Exception {
    List<String> flags = new ArrayList<>(config.jvmFlags);
    flags.addAll(wrapperArgs.jvmFlagsCmdline);
    Map<String, String> properties = new java.util.LinkedHashMap<>();
    properties.put("bazel.persistent.coverage", Boolean.toString(config.coverageMainClass != null));
    flags.removeIf(
        flag -> {
          if (flag.startsWith("-Dbazel.test_suite=")) {
            properties.put("bazel.test_suite", flag.substring("-Dbazel.test_suite=".length()));
            return true;
          }
          return false;
        });
    if (!properties.containsKey("bazel.test_suite")) {
      throw new IOException("Reusable JUnit request is missing -Dbazel.test_suite");
    }
    if (environment.containsKey("TEST_TMPDIR")) {
      properties.put("java.io.tmpdir", environment.get("TEST_TMPDIR"));
    }
    List<String> driverClasspath = classpath.subList(0, config.driverClasspath.size());
    properties.put(
        "user.dir",
        TestEnvironment.workingDirectory(
                environment, config.execPaths ? config.execRoot : Paths.get("").toAbsolutePath())
            .toString());
    environment = new java.util.HashMap<>(environment);
    environment.put("PWD", properties.get("user.dir"));
    List<String> testClasspath = classpath.subList(config.driverClasspath.size(), classpath.size());
    StringBuilder key = new StringBuilder(Paths.get(javabin).toRealPath().toString());
    key.append("\ncoverage=").append(config.coverageMainClass != null);
    for (String flag : flags) {
      key.append('\n').append(flag);
    }
    for (String path : driverClasspath) {
      key.append('\n').append(path).append('=').append(ReusableJvm.digest(Paths.get(path)));
    }
    if (extensionsFile != null) {
      key.append("\nextensions=").append(ReusableJvm.digest(Paths.get(extensionsFile)));
    }
    for (String name :
        java.util.Arrays.asList("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
      key.append('\n').append(name).append('=').append(environment.get(name));
    }
    List<JvmProcess.ClasspathEntry> requested = new ArrayList<>();
    for (int i = 0; i < config.classpath.size(); i++) {
      String path = testClasspath.get(i);
      requested.add(
          new JvmProcess.ClasspathEntry(
              config.classpath.get(i).label, path, ReusableJvm.digest(Paths.get(path))));
    }
    ReusableJvm child = null;
    for (java.util.Iterator<ReusableJvm> iterator = reusableChildren.iterator();
        iterator.hasNext(); ) {
      ReusableJvm candidate = iterator.next();
      if (candidate.compatible(key.toString(), requested)) {
        child = candidate;
        stderr.println("[persistent-test-runner] reusing warm child JVM");
        break;
      }
      if (candidate.startupKey.equals(key.toString()) || !candidate.process.isAlive()) {
        iterator.remove();
        candidate.close();
      }
    }
    if (child == null) {
      if (reusableChildren.size() >= MAX_JVMS) {
        reusableChildren.remove(0).close();
      }
      List<String> command = new ArrayList<>();
      command.add(javabin);
      command.addAll(flags);
      // Java 8 has no module boundary; newer runtimes require an explicit open.
      Path release = Paths.get(javabin).toRealPath().getParent().getParent().resolve("release");
      if (!Files.exists(release) && release.getParent().getFileName().toString().equals("jre")) {
        release = release.getParent().getParent().resolve("release");
      }
      String runtime =
          new String(Files.readAllBytes(release), java.nio.charset.StandardCharsets.UTF_8);
      java.util.regex.Matcher version =
          java.util.regex.Pattern.compile("JAVA_VERSION=\"(?:1\\.)?(\\d+)").matcher(runtime);
      if (!version.find()) {
        throw new IOException("Cannot determine Java runtime version from " + release);
      }
      int runtimeMajor = Integer.parseInt(version.group(1));
      if (runtimeMajor >= 9) {
        command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        command.add("--add-opens=java.base/sun.nio.fs=ALL-UNNAMED");
        if (runtimeMajor >= 17) {
          command.add("--enable-native-access=ALL-UNNAMED");
        }
      }
      command.add("-Dbazel.persistent.reusable_child=true");
      if (extensionsFile != null) {
        command.add("-Dbazel.persistent.extensions_file=" + extensionsFile);
      }
      command.add("-classpath");
      command.add(String.join(File.pathSeparator, driverClasspath));
      command.add(config.driverMain);
      long timeout =
          Long.parseLong(System.getProperty("bazel.persistent.startup_timeout_seconds", "30"));
      if (timeout <= 0 || timeout > Long.MAX_VALUE / 1_000_000_000) {
        throw new IllegalArgumentException("Invalid reusable child startup timeout");
      }
      child =
          ReusableJvm.start(
              key.toString(), command, environment, store, stdout, stderr, timeout * 1000);
      reusableChildren.add(child);
      stderr.println("[persistent-test-runner] spawned reusable child JVM");
    }
    try {
      return child.execute(
          new TestStart(testClasspath, wrapperArgs.programArgs, environment, properties),
          requested,
          stdout,
          stderr);
    } catch (Exception e) {
      reusableChildren.remove(child);
      child.close();
      throw e;
    }
  }

  private synchronized void closeChildren() {
    for (ReusableJvm child : reusableChildren) {
      child.close();
    }
    reusableChildren.clear();
    pool.close();
  }

  private static long remainingNanos(long started, long budget) {
    return Math.max(0, budget - (System.nanoTime() - started));
  }

  private static boolean joinWithin(Thread thread, long started, long budget)
      throws InterruptedException {
    long remaining = remainingNanos(started, budget);
    if (remaining > 0) {
      TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
    }
    return !thread.isAlive();
  }

  private Integer runSession(
      WireChannel channel, Process process, long startupStarted, long startupTimeoutMillis)
      throws IOException, InterruptedException {
    CompletableFuture<Integer> finished = new CompletableFuture<>();
    CompletableFuture<Void> ready = new CompletableFuture<>();
    Thread reader =
        new Thread(
            () -> {
              try {
                while (true) {
                  Message msg = channel.read();
                  if (msg == null) {
                    // Let the request thread apply the shutdown budget and obtain
                    // the exit status, rather than waiting unboundedly in this reader.
                    finished.complete(null);
                    return;
                  }
                  if (!ready.isDone() && !(msg instanceof SessionReady)) {
                    throw new IOException(
                        "Expected SessionReady during child startup, got "
                            + msg.getClass().getSimpleName());
                  }
                  handle(channel, msg, finished);
                  if (msg instanceof SessionReady) {
                    ready.complete(null);
                  }
                  if (finished.isDone()) {
                    return;
                  }
                }
              } catch (IOException | RuntimeException e) {
                ready.completeExceptionally(e);
                finished.completeExceptionally(e);
              }
            },
            "persistent-test-runner-wire-reader");
    reader.setDaemon(true);
    reader.start();

    channel.send(SessionStart.INSTANCE);
    try {
      while (!ready.isDone()) {
        if (!process.isAlive()) {
          throw new IOException(
              "Child exited with status "
                  + process.exitValue()
                  + " before SessionReady; see child stderr above");
        }
        long remaining =
            startupTimeoutMillis == 0
                ? Long.MAX_VALUE
                : java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(startupTimeoutMillis)
                    - (System.nanoTime() - startupStarted);
        if (remaining <= 0) {
          throw new IOException(
              "Child startup timed out after "
                  + startupTimeoutMillis
                  + " ms waiting for SessionReady (including FIFO connection)");
        }
        try {
          ready.get(
              Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50)),
              java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
          // Recheck process exit and the shared startup deadline.
        }
      }
      ready.get(); // Surface reader/protocol errors even if completion preceded the loop.
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
      Optional<StoreValue> v = store.get(r.key());
      channel.send(new StoreGetResponse(r.requestId(), v.isPresent(), v.orElse(null)));
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

  private void logSpawnedJvm(JvmProcess p, PrintStream stderr) {
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
    stderr.print(sb);
  }

  private static Thread drain(InputStream in, PrintStream out, String name) {
    Thread t =
        new Thread(
            () -> {
              byte[] buf = new byte[4096];
              try {
                int n;
                while ((n = in.read(buf)) != -1) {
                  synchronized (out) {
                    out.write(buf, 0, n);
                    out.flush();
                  }
                }
              } catch (IOException ignored) {
              }
            },
            name);
    t.setDaemon(true);
    t.start();
    return t;
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

  private static String resolveConfigPath(Config config, Runfiles runfiles, String path) {
    if (config.execPaths) {
      // The absolute config location records the execution root even when
      // test-setup.sh has changed cwd for single-shot execution.
      return config.execRoot.resolve(path).normalize().toString();
    }
    return resolveRunfile(runfiles, path);
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
        cp.append("file:")
            .append(p.startsWith("/") ? p : Paths.get("").toAbsolutePath().resolve(p));
      }
    }
    Manifest manifest = new Manifest();
    Attributes main = manifest.getMainAttributes();
    main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    main.putValue("Created-By", "Bazel");
    main.putValue("Class-Path", cp.toString());
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {}
    return jar;
  }
}
