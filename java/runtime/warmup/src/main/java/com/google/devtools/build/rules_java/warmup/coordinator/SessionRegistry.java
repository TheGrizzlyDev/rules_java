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

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Coordinator's view of live engine sessions. Reads are lock-free; iteration returns a snapshot of
 * whatever sessions were registered at the moment the view was taken.
 */
final class SessionRegistry {

  private final ConcurrentMap<Integer, Session> sessions = new ConcurrentHashMap<>();

  void register(Session session) {
    sessions.put(session.id(), session);
  }

  void remove(int id) {
    sessions.remove(id);
  }

  Session get(int id) {
    return sessions.get(id);
  }

  Collection<Session> all() {
    return sessions.values();
  }

  /** Returns any session currently in the WARMING state, if one exists. */
  Optional<Session> pickWarming() {
    for (Session s : sessions.values()) {
      if (s.state() == Session.State.WARMING) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }
}
