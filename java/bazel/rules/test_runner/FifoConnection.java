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

import com.google.devtools.build.java.testrunner.wire.WireChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Opens the coordinator's FIFO pair without waiting forever for a missing child. */
final class FifoConnection {
  private FifoConnection() {}

  /** A zero timeout permits intentional debugger suspension, but still detects child exit. */
  static WireChannel open(Process child, Path c2j, Path j2c, long timeoutMillis)
      throws IOException, InterruptedException {
    CompletableFuture<WireChannel> connected = new CompletableFuture<>();
    Thread opener =
        new Thread(
            () -> {
              OutputStream out = null;
              InputStream in = null;
              try {
                // Mirror the child: coordinator opens its write side, then its read side.
                out = new java.io.FileOutputStream(c2j.toFile());
                in = new java.io.FileInputStream(j2c.toFile());
                WireChannel channel = new WireChannel(in, out);
                if (connected.complete(channel)) {
                  out = null;
                  in = null;
                } else {
                  channel.close();
                }
              } catch (IOException e) {
                connected.completeExceptionally(e);
              } finally {
                closeQuietly(in);
                closeQuietly(out);
              }
            },
            "persistent-test-runner-fifo-open");
    opener.setDaemon(true);
    opener.start();
    long started = System.nanoTime();
    boolean success = false;
    try {
      while (true) {
        if (!child.isAlive()) {
          throw new IOException(
              "Child exited with status "
                  + child.exitValue()
                  + " before FIFO connection; see child stderr above");
        }
        long remaining =
            timeoutMillis == 0
                ? Long.MAX_VALUE
                : TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
        if (remaining <= 0) {
          throw new IOException(
              "Timed out after " + timeoutMillis + " ms waiting for child FIFO connection");
        }
        try {
          WireChannel channel =
              connected.get(
                  Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
          success = true;
          return channel;
        } catch (TimeoutException e) {
          // Recheck child exit and the shared deadline for both pipe opens.
        } catch (ExecutionException e) {
          throw new IOException("Failed to open child FIFOs", e.getCause());
        }
      }
    } finally {
      if (!success) {
        // Cancellation also prevents a late completion from transferring ownership.
        connected.cancel(false);
        child.destroyForcibly();
        // Interrupting a native FIFO open is insufficient. O_RDWR on a FIFO opens
        // immediately on supported POSIX systems and supplies both missing peers.
        // Keep both peers open until the opener has finished, then close everything.
        try (SeekableByteChannel writePeer =
                Files.newByteChannel(c2j, StandardOpenOption.READ, StandardOpenOption.WRITE);
            SeekableByteChannel readPeer =
                Files.newByteChannel(j2c, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
          opener.join(1000);
        }
        // The opener may have completed just before cancellation.
        if (!connected.isCompletedExceptionally()) {
          closeQuietly(connected.getNow(null));
        }
      }
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception ignored) {
      }
    }
  }
}
