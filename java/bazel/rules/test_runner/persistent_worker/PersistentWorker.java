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

import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a {@link WorkHandler} either as a persistent worker (Bazel proto protocol on stdin/stdout)
 * or as a one-shot invocation. Detects worker mode from a lone {@code --persistent_worker} argv.
 *
 * <p>Singleplex only; multiplexing and cancellation are not yet implemented.
 */
public final class PersistentWorker {

  private static final String WORKER_FLAG = "--persistent_worker";

  private PersistentWorker() {}

  /** Entry point for tools. Never returns from worker mode until stdin EOF. */
  public static void run(String[] argv, WorkHandler handler) throws Exception {
    if (argv.length == 1 && WORKER_FLAG.equals(argv[0])) {
      workerLoop(handler);
    } else {
      // One-shot: no capture, use real stdout/stderr.
      System.exit(handler.handle(expandArgFiles(argv), System.out, System.err));
    }
  }

  private static void workerLoop(WorkHandler handler) throws IOException {
    // The real stdout is the protocol channel. Anything the handler writes to System.out via
    // library code would corrupt the wire, so we swap System.out and System.err with capture
    // streams for the duration of the loop.
    InputStream in = System.in;
    OutputStream protocolOut = System.out;
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;

    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream capturedStream;
    try {
      capturedStream = new PrintStream(captured, true, StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      throw new AssertionError("UTF-8 always supported", e);
    }
    System.setOut(capturedStream);
    System.setErr(capturedStream);
    try {
      while (true) {
        WorkRequest request = WorkRequest.parseDelimitedFrom(in);
        if (request == null) {
          return; // EOF, clean shutdown.
        }
        // TODO: honor request.cancel and request.request_id for multiplexing/cancellation.
        WorkResponse response = handle(handler, request, captured, capturedStream);
        response.writeDelimitedTo(protocolOut);
        protocolOut.flush();
      }
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }

  private static WorkResponse handle(
      WorkHandler handler,
      WorkRequest request,
      ByteArrayOutputStream captured,
      PrintStream capturedStream) {
    captured.reset();
    int exitCode;
    try {
      exitCode = handler.handle(expandArgFiles(request.getArgumentsList()), capturedStream, capturedStream);
    } catch (Throwable t) {
      StringWriter sw = new StringWriter();
      t.printStackTrace(new PrintWriter(sw));
      capturedStream.print(sw);
      exitCode = 1;
    }
    capturedStream.flush();
    String output;
    try {
      output = captured.toString(StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      output = captured.toString();
    }
    return WorkResponse.newBuilder()
        .setRequestId(request.getRequestId())
        .setExitCode(exitCode)
        .setOutput(output)
        .build();
  }

  /** Splices any {@code @paramfile} arg into its lines, in place. Escapes {@code @@} → {@code @}. */
  private static List<String> expandArgFiles(String[] argv) throws IOException {
    List<String> in = new ArrayList<>(argv.length);
    for (String a : argv) {
      in.add(a);
    }
    return expandArgFiles(in);
  }

  private static List<String> expandArgFiles(List<String> argv) throws IOException {
    List<String> out = new ArrayList<>(argv.size());
    for (String arg : argv) {
      if (arg.startsWith("@@")) {
        out.add("@" + arg.substring(2));
      } else if (arg.startsWith("@")) {
        for (String line : Files.readAllLines(Paths.get(arg.substring(1)), StandardCharsets.UTF_8)) {
          out.add(line);
        }
      } else {
        out.add(arg);
      }
    }
    return out;
  }
}
