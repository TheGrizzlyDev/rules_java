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
package com.google.devtools.build.java.testrunner.persistent_worker;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class PersistentWorkerTest {

  @Test
  public void workerMode_prependsStickyPrefixToEveryRequest() throws Exception {
    ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
    WorkRequest.newBuilder()
        .setRequestId(1)
        .addArguments("first")
        .build()
        .writeDelimitedTo(requestBytes);
    WorkRequest.newBuilder()
        .setRequestId(2)
        .addArguments("second")
        .addArguments("also-second")
        .build()
        .writeDelimitedTo(requestBytes);

    List<List<String>> seen = new ArrayList<>();
    WorkHandler handler =
        (args, stdout, stderr) -> {
          seen.add(new ArrayList<>(args));
          return 0;
        };

    ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
    withStdio(
        new ByteArrayInputStream(requestBytes.toByteArray()),
        new PrintStream(responseBytes),
        () ->
            PersistentWorker.run(
                new String[] {"--config=/tmp/cfg.pb", "--", "--persistent_worker"}, handler));

    assertThat(seen).hasSize(2);
    assertThat(seen.get(0)).isEqualTo(Arrays.asList("--config=/tmp/cfg.pb", "--", "first"));
    assertThat(seen.get(1))
        .isEqualTo(Arrays.asList("--config=/tmp/cfg.pb", "--", "second", "also-second"));

    ByteArrayInputStream in = new ByteArrayInputStream(responseBytes.toByteArray());
    WorkResponse r1 = WorkResponse.parseDelimitedFrom(in);
    WorkResponse r2 = WorkResponse.parseDelimitedFrom(in);
    assertThat(r1.getRequestId()).isEqualTo(1);
    assertThat(r1.getExitCode()).isEqualTo(0);
    assertThat(r2.getRequestId()).isEqualTo(2);
    assertThat(r2.getExitCode()).isEqualTo(0);
  }

  @Test
  public void workerMode_flagInAnyPositionEntersLoop() throws Exception {
    ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
    WorkRequest.newBuilder().setRequestId(7).build().writeDelimitedTo(requestBytes);

    List<List<String>> seen = new ArrayList<>();
    WorkHandler handler =
        (args, stdout, stderr) -> {
          seen.add(new ArrayList<>(args));
          return 0;
        };

    ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
    withStdio(
        new ByteArrayInputStream(requestBytes.toByteArray()),
        new PrintStream(responseBytes),
        () -> PersistentWorker.run(new String[] {"--persistent_worker", "--config=x"}, handler));

    assertThat(seen).hasSize(1);
    assertThat(seen.get(0)).containsExactly("--config=x").inOrder();
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void withStdio(InputStream in, PrintStream out, ThrowingRunnable body)
      throws Exception {
    InputStream originalIn = System.in;
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    System.setIn(in);
    System.setOut(out);
    try {
      body.run();
    } finally {
      System.setIn(originalIn);
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }
}
