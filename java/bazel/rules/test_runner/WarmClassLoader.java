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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Retains loaded classes and static state, with discovery and resources scoped to each dispatch.
 *
 * <p>JDK services such as Charset use this system loader to discover test-provided services.
 */
public final class WarmClassLoader extends URLClassLoader {
  private final Set<String> paths = new HashSet<>();
  private final Set<Path> bootstrapPaths = new HashSet<>();
  private final Set<Path> validatedPaths = new HashSet<>();
  private volatile boolean unvalidatedInstrumentation;
  private volatile URL[] currentUrls = new URL[0];
  private volatile URLClassLoader resources = new URLClassLoader(currentUrls, null);

  // The JVM invokes this constructor when java.system.class.loader names this class.
  public WarmClassLoader(ClassLoader parent) throws IOException {
    super(new URL[0], parent);
    for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) {
      Path bootstrap = Paths.get(path);
      if (Files.exists(bootstrap)) {
        bootstrapPaths.add(bootstrap.toRealPath());
      }
    }
    validatedPaths.addAll(bootstrapPaths);
  }

  synchronized void append(String path) throws IOException {
    if (paths.add(path)) {
      addURL(Paths.get(path).toUri().toURL());
    }
  }

  // The JVM requires this hook on custom system loaders when an agent appends
  // to the system search path. Agent classes join the warm class lookup without
  // changing the current test's resource or classpath discovery view.
  public synchronized void appendToClassPathForInstrumentation(String path) throws IOException {
    Path canonical = Paths.get(path).toRealPath();
    if (!validatedPaths.contains(canonical)) {
      // The coordinator has no digest for this jar. Finish this request normally,
      // then retire the child instead of reusing unvalidated agent code.
      unvalidatedInstrumentation = true;
    }
    append(canonical.toString());
  }

  boolean mustRetire() {
    return unvalidatedInstrumentation;
  }

  synchronized void setClasspath(List<String> classpath) throws IOException {
    validatedPaths.clear();
    validatedPaths.addAll(bootstrapPaths);
    URL[] urls = new URL[classpath.size()];
    for (int i = 0; i < classpath.size(); i++) {
      String path = classpath.get(i);
      validatedPaths.add(Paths.get(path).toRealPath());
      urls[i] = Paths.get(path).toUri().toURL();
      append(path);
    }
    URLClassLoader previous = resources;
    resources = new URLClassLoader(urls, null);
    currentUrls = urls;
    previous.close();
  }

  // Classpath scanners must see this test's jars, while findClass and already
  // loaded classes continue to use the deliberately warm accumulated loader.
  @Override
  public URL[] getURLs() {
    return currentUrls.clone();
  }

  @Override
  public URL findResource(String name) {
    return resources.findResource(name);
  }

  @Override
  public Enumeration<URL> findResources(String name) throws IOException {
    return resources.findResources(name);
  }

  @Override
  public void close() throws IOException {
    try {
      resources.close();
    } finally {
      super.close();
    }
  }
}
