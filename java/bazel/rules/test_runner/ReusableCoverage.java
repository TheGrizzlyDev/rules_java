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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** Collects a dispatch's offline JaCoCo probes without shutting down the warm JVM. */
final class ReusableCoverage implements AutoCloseable {
  private final ClassLoader loader;
  private final File[] metadata;
  private final Path directory;
  private final Method getAgent;
  private final Class<?> agentInterface;

  ReusableCoverage(ClassLoader loader, List<String> classpath, Map<String, String> environment)
      throws Exception {
    this.loader = loader;
    this.metadata = classpath.stream().map(File::new).toArray(File[]::new);
    this.directory = Paths.get(environment.get("COVERAGE_DIR"));
    Files.createDirectories(directory);
    // Offline instrumentation initializes the agent lazily. Disable its exit-time
    // dump before test classes initialize; each dispatch owns its report instead.
    System.setProperty("jacoco-agent.output", "none");
    System.setProperty("jacoco-agent.sessionid", "persistent-test");
    this.getAgent = Class.forName("org.jacoco.agent.rt.RT", true, loader).getMethod("getAgent");
    this.agentInterface = Class.forName("org.jacoco.agent.rt.IAgent", true, loader);
    Object agent = agent();
    if (agent != null) {
      agentInterface.getMethod("reset").invoke(agent);
    }
  }

  private Object agent() throws Exception {
    try {
      return getAgent.invoke(null);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof IllegalStateException) {
        // No instrumented class has executed yet; an empty report is still valid.
        return null;
      }
      throw e;
    }
  }

  @Override
  public void close() throws Exception {
    Object agent = agent();
    byte[] data =
        agent == null
            ? new byte[0]
            : (byte[])
                agentInterface.getMethod("getExecutionData", boolean.class).invoke(agent, true);
    Path execution = Files.createTempFile(directory, "jvcov", ".exec");
    Files.write(execution, data);
    Path report = Files.createTempFile(directory, "jvcov", ".dat");
    Class<?> runner =
        Class.forName("com.google.testing.coverage.JacocoCoverageRunner", true, loader);
    Object collector =
        runner
            .getConstructor(InputStream.class, String.class, File[].class)
            .newInstance(new ByteArrayInputStream(data), report.toString(), metadata);
    // Analyze only this request's jars, even if the loader retains additional jars.
    runner.getMethod("create").invoke(collector);
  }
}
