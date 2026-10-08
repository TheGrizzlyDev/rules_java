# Persistent Java test workers

Persistent workers preserve warmed child JVMs across different test targets. Retained
classloaders, static state, system properties, context, and threads are intentional:
class loading and JVM warming are the main benefit of this API. Tests opting into
this execution model accept state left by earlier tests. General state resets are
outside this design.

## Reuse policy

Validate the jars already attached to a child by identity and SHA-256 digest before
reuse. A conflicting or unreadable jar must prevent reuse. Compatible additional
jars can extend the existing classloader; the loader and its loaded classes stay
warm. JVM executable, startup flags, driver code, and extension identity must also
remain compatible.

Per-request test metadata (suite, arguments, filter, sharding, XML and output paths)
must reach the next execution without restarting the child. Updating this metadata
is distinct from resetting application state. Runfiles need separate handling:
retain the original target's runfiles context and resolve its paths for each request.

## Implementation order

1. Add repeated dispatch and a returning test entry point.
2. Prove that different tests share a child PID and JVM startup token, including a
   pass, fail, pass sequence. Run the proof in rules_java and on a subset of Bazel's
   existing Java tests. Capture results and XML separately for each request.
3. Verify jar digest compatibility and invalidation when jars change. Complete
   per-request runfiles handling.
4. Complete store failure propagation, pool cleanup, concurrency, cancellation,
   coverage, and performance measurements.

The first acceptance milestone is observed reuse of the same child across distinct
tests, rather than inference from coordinator reuse or passing tests alone.

## Current execution scope

The retained child path supports the standard Bazel JUnit runner. Arbitrary main
classes, debugging, main advice, and coverage currently use the existing fresh
child path. The coordinator uses the public returning `BazelTestRunner.runTests`
entry when available; an adapter calls `runTestsInSuite` in older runner jars.

One append-only URL classloader retains the test jars and loaded classes. The
compatibility check conservatively fingerprints every attached jar, including
jars whose classes may not have loaded yet. Different suites and compatible added
jars can share it. A changed digest causes the stale child to shut down and a new
child to start. Failed or missing digest reads never count as a matching digest.

Only runner-owned metadata changes between requests: suite, environment, temporary
directory, and output destinations. Java's environment view is updated so existing
JUnit code can read the current filter, shard, and XML path through `System.getenv`.
Modular JDKs require opening `java.util` for that operation. Native-library `getenv`,
native working-directory changes, and complete runfiles reconstruction remain
separate work. General JVM state is retained.

## Reproducing child reuse

Run the integration rig from a Linux environment that can execute the custom Bazel
binary and selected JDKs:

```sh
bash test/java/bazel/rules/test_runner/child_reuse_test.sh \
  /absolute/path/to/custom/bazel /absolute/path/to/bazel/checkout
```

The repo group runs both warm-state fixtures and the existing wire codec suite.
The optional second argument adds Bazel's `CollectionUtilsTest`, `ExtremaTest`, and
`ImmutableSharedKeyMapTest`. The rig uses a local rules_java override and serial
execution, then asserts the same child PID and startup token across targets,
different dispatch counters, and successful XML results for every suite. The repo
fixtures additionally assert retained classloader state and a static counter.
Logs, XML, and `summary.json` remain in the printed proof directory. Use
`JVM_REUSE_PROOF_DIR` to select a destination and `BAZEL_REUSE_OUTPUT_BASE` to reuse
an existing isolated Bazel output base. Keep the action PATH stable across build
and test commands; `JVM_REUSE_ACTION_PATH` and `BAZEL_REUSE_ACTION_PATH` can select
those environments when a platform adapter changes PATH between shells.

`ChildReuseTest` sends an ordered pass, fail, pass sequence through a real worker
coordinator. It verifies the same child and warm static state survive the failed
suite, then changes a jar digest and requires a fresh child and classloader. It also
verifies cleanup when the coordinator's request stream reaches EOF.

## Observed reuse evidence

The Linux integration run on 2026-10-08 passed with the local custom Bazel and this
rules_java override. Every target in each group reported the same child PID and
startup token, with distinct dispatch counts. XML results contained the expected
suite classes and no failures.

| Test group | Distinct targets | Passing test cases | Shared child PID |
| --- | ---: | ---: | ---: |
| rules_java warm fixtures and wire codec suite | 3 | 23 | 1783185 |
| Bazel collection suites | 3 | 22 | 1784939 |

The repo child startup token was `d1895d00-585e-4a01-b6f8-d084f672f617`; the Bazel
child token was `4b988b7d-6dab-425f-a3cd-4e8507b7193c`. The proof rig checks these
identities independently of Bazel's coordinator worker identity. It retains copied
logs and XML plus a JSON summary; this run's artifacts are at
`/tmp/rules-java-jvm-reuse.gRAyNN` on the development machine.

All fifteen selected worker regressions passed. The ordered-dispatch regression
kept child PID `1775729` through First, Failure, and Second, preserving its warm
static token. Changing a jar digest started fresh child PID `1776056`, and closing
the coordinator request stream terminated its pooled child. Bazel's direct
`ReturningRunnerTest` also passed its public-entry pass, fail, pass assertions.
The three repo proof targets also passed with persistent workers disabled and
the standalone test strategy.

These results establish actual child reuse under serial execution. Runfiles,
concurrent execution, and a performance benchmark remain separate milestones.
