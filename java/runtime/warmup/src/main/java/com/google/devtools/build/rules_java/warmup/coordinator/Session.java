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
package com.google.devtools.build.rules_java.warmup.coordinator;

import com.google.devtools.build.rules_java.warmup.common.Profile;
import com.google.devtools.build.rules_java.warmup.common.wire.Codec;
import com.google.devtools.build.rules_java.warmup.common.wire.PromoteRequest;
import com.google.devtools.build.rules_java.warmup.common.wire.StateStaleNotice;
import com.google.devtools.build.rules_java.warmup.common.wire.WarmupUpdate;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * One accepted engine connection. Owns the socket, exposes an outbound API for the coordinator to
 * push events at this specific engine, and tracks a small lifecycle.
 *
 * <p>Reads happen on the connection thread through {@link #input()}. Writes go through the {@code
 * send*} methods, which serialize with state transitions on a single monitor. State reads may
 * observe a stale value; callers must not depend on read-then-write atomicity across methods.
 */
final class Session {

  enum State {
    /** Socket accepted, no snapshot exchanged yet. */
    CONNECTED,
    /** Snapshot reply sent; engine is applying it and reading the event stream. */
    WARMING,
    /** PromoteRequest sent. Engine has stopped consuming warmup events. */
    PROMOTED,
    /** Connection closed (peer close, I/O error, or explicit close). */
    DEAD,
  }

  private final int id;
  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;
  private final Object lock = new Object();
  private State state = State.CONNECTED;

  Session(int id, Socket socket) throws IOException {
    this.id = id;
    this.socket = socket;
    this.in = socket.getInputStream();
    this.out = socket.getOutputStream();
  }

  int id() {
    return id;
  }

  State state() {
    synchronized (lock) {
      return state;
    }
  }

  /** Reader-thread view of the input stream. Not guarded — only the connection thread reads. */
  InputStream input() {
    return in;
  }

  /** Sends the initial profile in response to a SnapshotRequest and transitions to WARMING. */
  void sendSnapshotReply(Profile profile) throws IOException {
    synchronized (lock) {
      requireState(State.CONNECTED);
      Codec.writeProfile(out, profile);
      state = State.WARMING;
    }
  }

  void sendWarmupUpdate(WarmupUpdate update) throws IOException {
    synchronized (lock) {
      requireState(State.WARMING);
      Codec.writeServerEvent(out, update);
    }
  }

  void sendStaleNotice(StateStaleNotice notice) throws IOException {
    synchronized (lock) {
      requireState(State.WARMING);
      Codec.writeServerEvent(out, notice);
    }
  }

  /** Sends PromoteRequest and transitions to PROMOTED. */
  void sendPromoteRequest(PromoteRequest request) throws IOException {
    synchronized (lock) {
      requireState(State.WARMING);
      Codec.writeServerEvent(out, request);
      state = State.PROMOTED;
    }
  }

  /** Idempotent. Marks the session dead and closes the socket. */
  void close() {
    synchronized (lock) {
      if (state == State.DEAD) {
        return;
      }
      state = State.DEAD;
    }
    try {
      socket.close();
    } catch (IOException ignored) {
      // best effort
    }
  }

  private void requireState(State expected) {
    if (state != expected) {
      throw new IllegalStateException(
          "session " + id + ": expected state " + expected + " but was " + state);
    }
  }
}
