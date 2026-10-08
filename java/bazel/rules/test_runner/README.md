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
Modular JDKs require opening `java.util` for that operation. Native-library `getenv`
and working-directory changes remain separate work. General JVM state is retained.

## Per-request runfiles

The Bazel fork preserves the original test target's runfiles tree when substituting
the coordinator executable. Rule arguments and the action environment also remain
attached to the original target. `TEST_BINARY` and temporary-directory identity
also use the original executable, so distinct targets do not share the coordinator's
temp directory. Request setup resolves runfiles against the execution root and
supplies runfiles aliases and its manifest when omitted by the native request.
Inherited aliases from the coordinator launcher are discarded
when a request supplies its own `TEST_SRCDIR`; explicit request values win.

Each warm dispatch sets runner-owned `bazel.runfiles.context` metadata. The Java
runfiles library caches `Runfiles.preload()` within that execution, and reloads
the current directory or manifest and repository mapping on the next dispatch.
This also handles a manifest rewritten at the same pathname. Previously returned
objects retain their original target's context, including objects deliberately
kept in application statics. Tests should request a new runfiles object when they
want the current execution's files. Older runfiles libraries that do not understand
this context property still need their callers to use `Runfiles.preload(System.getenv())`.

`ChildReuseTest` alternates directory and manifest contexts with different mappings
for the same apparent `alias/data.txt` path. It verifies new lookups use the current
target, old retained objects still resolve their original data, a rewritten manifest
is reloaded, subprocesses resolve data using `Runfiles.getEnvVars()`, and the same
child survives five dispatches before jar invalidation. The real warm fixture
targets also read distinct `data` dependencies through the
current runfiles object. Native-library working directories and native `getenv`
remain separate work; this step supports explicit Java runfiles lookups.

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
Set `JVM_REUSE_MANIFEST_ONLY=1` to repeat the proof with symlink runfiles disabled.
Java tool actions declare the aggregator's sidecar manifest as an input, and the
Java launchers discover either `tree/MANIFEST` or the sibling `.runfiles_manifest`.
The test wrapper resolves its config through that manifest before invoking the
coordinator, including physical paths containing spaces.

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

These initial results establish actual child reuse under serial execution. The
follow-up runfiles milestone is recorded below.

## Runfiles acceptance evidence

The follow-up run on 2026-10-09 passed all seventeen worker and runfiles regression
targets in both directory and manifest-only modes. The updated directory proof
retained repo child PID `2106433` across three targets (23 passing cases) while
the two fixture targets read distinct runfiles trees. Bazel's three collection
targets retained child PID `2122466` across 22 passing cases. Copied logs, XML, and
startup tokens are in `/tmp/rules-java-jvm-reuse.HUzKQD`.

With symlink runfiles disabled, the same three repo targets shared child PID
`2158442` and startup token `f609d7d2-8ddc-474a-a10a-f87e3d63b8e8`; artifacts are in
`/tmp/rules-java-jvm-reuse.wrx4s8`. All three also passed under the standalone
strategy with persistent workers disabled. Direct invocation of the test wrapper
passed with only a sidecar manifest and spaces in the runner and config paths;
`wrapper-spaces.log` and `wrapper.xml` preserve that check in the manifest proof
directory.

The ordered regression kept child PID `2104138` across five runfiles contexts,
including a failed suite and a rewritten manifest, then started child PID
`2104293` after a jar digest changed. This step validates explicit Java runfiles
lookups and subprocess propagation. Native working directories, native `getenv`,
concurrency, cancellation, and coverage remain later work.
