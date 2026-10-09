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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        (args, environment, stdout, stderr) -> {
          seen.add(new ArrayList<>(args));
          return 0;
        };

    ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
    withStdio(
        new ByteArrayInputStream(requestBytes.toByteArray()),
        new PrintStream(responseBytes),
        () ->
            PersistentWorker.run(
                new String[] {"--config=/tmp/cfg.pb", "--persistent_worker", "--"}, handler));

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
        (args, environment, stdout, stderr) -> {
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

  @Test
  public void workerMode_cancelsRunningZeroIdThenReusesProtocol() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    AtomicInteger cleaned = new AtomicInteger();
    exerciseCancellation(
        0,
        false,
        (args, environment, stdout, stderr) -> {
          calls.incrementAndGet();
          if (args.get(0).equals("block")) {
            started.countDown();
            try {
              if (!new CountDownLatch(1).await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("Cancellation never interrupted the handler");
              }
            } finally {
              cleaned.incrementAndGet();
            }
          }
          if (Thread.currentThread().isInterrupted()) {
            throw new AssertionError("Cancellation interrupt leaked into the next request");
          }
          stdout.print("after");
          return 0;
        },
        started);
    assertThat(calls.get()).isEqualTo(2);
    assertThat(cleaned.get()).isEqualTo(1);
  }

  @Test
  public void workerMode_skipsCancelledQueuedRequest() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(1);
    exerciseCancellation(
        1,
        true,
        (args, environment, stdout, stderr) -> {
          calls.incrementAndGet();
          if (args.get(0).equals("queued")) {
            throw new AssertionError("A cancelled queued request executed");
          }
          if (args.get(0).equals("block")) {
            started.countDown();
            if (!new CountDownLatch(1).await(3, TimeUnit.SECONDS)) {
              throw new AssertionError("Cancellation never interrupted the handler");
            }
          }
          stdout.print("after");
          return 0;
        },
        started);
    assertThat(calls.get()).isEqualTo(2);
  }

  private static void exerciseCancellation(
      int id, boolean queued, WorkHandler handler, CountDownLatch started) throws Exception {
    try (PipedInputStream requests = new PipedInputStream();
        PipedOutputStream client = new PipedOutputStream(requests);
        PipedInputStream responses = new PipedInputStream();
        PipedOutputStream protocol = new PipedOutputStream(responses)) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread producer =
          new Thread(
              () -> {
                try {
                  send(
                      client,
                      WorkRequest.newBuilder().setRequestId(id).addArguments("block").build());
                  if (!started.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Handler never started");
                  }
                  if (queued) {
                    send(
                        client,
                        WorkRequest.newBuilder().setRequestId(2).addArguments("queued").build());
                    send(client, WorkRequest.newBuilder().setRequestId(2).setCancel(true).build());
                  }
                  send(client, WorkRequest.newBuilder().setRequestId(id).setCancel(true).build());
                  send(client, WorkRequest.newBuilder().setRequestId(id).setCancel(true).build());
                  WorkResponse cancelled = WorkResponse.parseDelimitedFrom(responses);
                  assertThat(cancelled.getRequestId()).isEqualTo(id);
                  assertThat(cancelled.getWasCancelled()).isTrue();
                  assertThat(cancelled.getExitCode()).isEqualTo(0);
                  assertThat(cancelled.getOutput()).isEmpty();
                  if (queued) {
                    WorkResponse skipped = WorkResponse.parseDelimitedFrom(responses);
                    assertThat(skipped.getRequestId()).isEqualTo(2);
                    assertThat(skipped.getWasCancelled()).isTrue();
                  }
                  // Neither an unknown cancellation nor one arriving after completion
                  // may create an extra response or interrupt the next request.
                  send(client, WorkRequest.newBuilder().setRequestId(99).setCancel(true).build());
                  send(client, WorkRequest.newBuilder().setRequestId(id).setCancel(true).build());
                  int next = queued ? 3 : 0;
                  send(
                      client,
                      WorkRequest.newBuilder().setRequestId(next).addArguments("after").build());
                  WorkResponse recovered = WorkResponse.parseDelimitedFrom(responses);
                  assertThat(recovered.getRequestId()).isEqualTo(next);
                  assertThat(recovered.getWasCancelled()).isFalse();
                  assertThat(recovered.getExitCode()).isEqualTo(0);
                  assertThat(recovered.getOutput()).isEqualTo("after");
                } catch (Throwable t) {
                  failure.set(t);
                } finally {
                  try {
                    client.close();
                  } catch (Exception e) {
                    failure.compareAndSet(null, e);
                  }
                }
              },
              "cancellation-test-client");
      producer.setDaemon(true);
      producer.start();
      withStdio(
          requests,
          new PrintStream(protocol),
          () -> PersistentWorker.run(new String[] {"--persistent_worker"}, handler));
      producer.join(5000);
      assertThat(producer.isAlive()).isFalse();
      if (failure.get() != null) {
        throw new AssertionError("Cancellation protocol failed", failure.get());
      }
      protocol.close();
      assertThat(WorkResponse.parseDelimitedFrom(responses)).isNull();
    }
  }

  private static void send(PipedOutputStream output, WorkRequest request) throws Exception {
    request.writeDelimitedTo(output);
    output.flush();
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
