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
   per-request runfiles and native execution-context handling.
4. Validate actual Bazel timeout and interruption routing through worker cancellation
   and sandbox execution, including child cleanup and subsequent worker recovery.
5. Run a small performance comparison against ordinary Bazel test execution.
6. Validate ordinary coverage and per-request coverage collection in retained JVMs.

All six steps have passed their acceptance checks. Evidence and reproduction
commands are recorded below.

Parallel dispatch is deferred until multiplex persistent test workers are supported.
The provider currently declares `multiplex = False`, so Bazel sends one active test
request per coordinator. Multiple coordinators can already execute concurrently.
Exclusive child leases and concurrent pool scheduling belong to that future milestone.

Keep the benchmark small: use a few short existing Bazel Java test targets, with
the same Bazel binary, JDK, targets, and execution concurrency for both strategies.
Compare ordinary standalone execution with persistent execution after building the
inputs and warming Bazel's server. Disable test-result caching, alternate strategy
order over a few repetitions, and report elapsed time and per-test execution times.
Record cold worker startup separately from runs retaining workers, and check child
identities to establish that the measured persistent runs actually reused JVMs.

Coverage dispatches collect JaCoCo execution data and write a report before the
worker response. Probes reset between requests so earlier tests' hits are not
attributed to later tests. This instrumentation reset preserves warm application
state. Class initializers execute once, so their coverage appears only in the
dispatch that initializes the class.

The first acceptance milestone is observed reuse of the same child across distinct
tests, rather than inference from coordinator reuse or passing tests alone.

## Current execution scope

The retained child path supports the standard Bazel JUnit runner on Linux. Arbitrary main
classes, debugging, main advice, and an explicitly configured system classloader
use the existing fresh-child path. Standard
JUnit coverage uses retained children too. The coordinator uses the public returning
`BazelTestRunner.runTests` entry when available; an adapter calls `runTestsInSuite`
in older runner jars.

One append-only URL classloader retains the test jars and loaded classes. It is
the JVM's system classloader so JDK service discovery, including charset providers,
can find services in the test jars. Classpath scanners and resource/service
enumeration see only the current request's jars; previously loaded classes and
their static state remain warm. This prevents a suite that scans its package from
discovering unrelated tests attached by an earlier request. The
compatibility check conservatively fingerprints every attached jar, including
jars whose classes may not have loaded yet. Different suites and compatible added
jars can share it. A changed digest causes the stale child to shut down and a new
child to start. Failed or missing digest reads never count as a matching digest.

Only runner-owned metadata changes between requests: suite, environment, classpath,
working and temporary directories, and output destinations. `java.class.path`
contains the driver bootstrap jars and current test jars so in-test compiler calls
use the current dependencies. Both Java and native code see
the current environment. General JVM state is retained.

Bazel's automatic codec registry treats its defining classpath as an input to its
generated metadata. It rescans when that classpath changes and retains the cached
registry when it is unchanged. This is a targeted library adaptation; the runner
does not clear application static fields or general JVM caches.

The system loader supports the JDK instrumentation append hook used by Mockito's
inline mock maker. An agent jar already covered by the current request's validated
classpath can remain warm. Appending an agent jar outside the validated inputs
retires the child after its result is delivered; the coordinator reaps it before
the next dispatch. This avoids reusing code whose jar digest it cannot validate.

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
current runfiles object. The native execution-context handling below extends this
to relative I/O and subprocesses that inherit their environment implicitly.

## Native execution context

The driver embeds a Linux JNI library in its bootstrap jar. The jar's existing
SHA-256 compatibility check therefore also validates the native implementation.
The library is extracted once per child, loaded, and immediately unlinked. Before
each suite, it changes the process directory and replaces libc's environment with
the prepared request environment. Removed variables disappear from both native
and Java views. Subprocesses inherit the current context whether or not the caller
materializes `ProcessBuilder.environment()`.

Directory-mode tests run in `TEST_SRCDIR/TEST_WORKSPACE`. A missing directory is a
request failure. Manifest-only tests without a physical workspace use the execution
root and resolve logical data paths through the runfiles library. Requests preserving
the working directory, coverage requests, and requests without `TEST_SRCDIR` use the
execution root. The coordinator sends that absolute directory as runner-owned
`user.dir` metadata and refreshes `PWD`. The driver also updates Java's cached `java.io` and NIO default
directories, so relative file operations and absolute-path resolution agree with
native `getcwd`. Modular JDKs open `java.util`, `java.io`, and `sun.nio.fs` for these
narrow metadata updates. Java 8 reads `user.dir` directly in `java.io`.

A context-update failure prevents suite execution and retires the child. These
updates are process-wide and suites dispatch serially within each child. Retained
application threads observe the current execution context; their state stays warm.
Other operating systems use the existing fresh-child path.

`ChildReuseTest` changes physical workspaces across dispatches, preserves the
execution root for one request, and alternates directory and manifest contexts.
It checks relative `FileOutputStream` and NIO access, Java absolute/canonical paths,
`/proc/self/cwd`, removed variables, Unicode environment values, and implicit and
explicit subprocess inheritance. The real warm fixtures exercise relative I/O
and inherited subprocess context too.

## Cancellation protocol

The worker reads protocol messages independently of its serial handler thread.
A cancellation message interrupts the matching running handler once; repeated,
late, and unknown cancellations never produce extra responses. A cancelled queued
request skips its handler. Each accepted work request still receives exactly one
response. Cancellation responses preserve the request ID, set `was_cancelled`,
and leave output and exit status empty. Request ID zero remains singleplex; positive
IDs can queue but handlers still dispatch serially.

Cancellation retires the affected reusable child. The coordinator waits for bounded
child-exit cleanup before returning, including during FIFO startup and fresh-child
execution. Interruption cannot skip that exit wait, and the handler thread clears
its cancellation status before the next request. Other warm children remain in the
pool. EOF finishes accepted requests and then closes retained children.

`CancellationTest` cancels a real JUnit suite that deliberately never returns,
requires its child PID to disappear before acknowledgement, and then proves two
new suites share a replacement JVM. It also cancels a fresh child and a driver
blocked before FIFO connection, requiring the replacement JVM's dispatch and warm
static counters to survive both operations. Protocol tests cover queued, duplicate,
late, and unknown cancellation. This implements the worker message contract;
Bazel's cancellation routing and sandbox integration are covered by the opt-in
`BazelCancellationFixture` and `bazel_cancellation_test.py` rig. Use
`--worker_sandboxing=JvmTestRunner=1 --experimental_worker_cancellation` and the
`supports-worker-cancellation` test tag. The Bazel fork maps that empty tag value
to the worker capability value `1`. Singleplex test requests honor the spawn's
timeout; sandboxed cancellable workers retire the child and return to the pool,
while other workers are invalidated on timeout. Parallel handler dispatch remains
future work.

Run `python3 test/java/bazel/rules/test_runner/bazel_cancellation_test.py
/absolute/custom/bazel --artifacts /tmp/cancellation-proof` in the supported Linux
build environment. The rig waits until the hanging suite reports its actual PID,
interrupts the Bazel client or waits for a two-second timeout, verifies the PID
disappears, and requires two subsequent tests to retain a replacement child through
the same coordinator.

For a baseline using the ordinary Java launcher, disable selection of this repo's
persistent toolchain with `--@rules_java//java/bazel/rules/test_runner:enabled=false`.
Disabling only `--enable_persistent_test_runners` still exercises the coordinator's
single-shot fallback when its toolchain is selected.

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
verifies that appending validated jars stays warm, appending an unknown agent jar
requires a fresh child for the next request, and coordinator EOF cleans up children.
`InstrumentationClassLoaderTest` starts a real Java agent and calls
`Instrumentation.appendToSystemClassLoaderSearch`, checking class loading, jar
validation, retained class identity, and the per-request resource view.

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
lookups and subprocess propagation. At that milestone, native working directories, native `getenv`, concurrency,
cancellation, and coverage remained later work.

## Native execution-context acceptance evidence

The 2026-10-09 follow-up passed all seventeen worker and runfiles regressions on
JDK 8. Four targeted tests also passed on JDK 21: ordered child reuse, working-directory
selection, and both real warm fixtures. The three standalone proof targets passed
with persistent workers disabled.

The updated cross-repository proof retained child PID `22548` across the three
rules_java targets (23 passing cases) and child PID `24343` across Bazel's three
collection targets on JDK 25 (22 passing cases). The fixture logs verify relative
I/O, current `PWD`, and implicit and explicit subprocess inheritance alongside
retained classloader state. Logs, XML, `summary.json`, and copied JDK 8 and JDK 21
ordered-dispatch results are in `/tmp/rules-java-jvm-reuse.YKhDrU`.

The helper supports the directory-cache layouts exercised by JDKs 8, 21, and 25.
The proof rig now applies this repo's test-specific build options during its build
phase too, avoiding unnecessary C++ dependency rebuilds between build and test.
At that milestone, concurrency, cancellation, coverage, and performance measurements
remained later work. The subsequent serial milestones are recorded below.

## Cancellation acceptance evidence

The next 2026-10-09 run passed all eighteen worker and runfiles regression targets
on JDK 8, including the new cancellation regression and four protocol unit cases.
All six targeted JDK 21 checks passed too. The JDK 8 cancellation regression retired
warm child PID `82705` in about one second, then retained replacement child PID
`82740` across four passing dispatches with increasing warm static counters.
Fresh-child and FIFO-startup cancellation completed in a few milliseconds. In every
case the regression checked that the cancelled PID had disappeared before receiving
an empty cancellation response, and checked final pooled-child cleanup at EOF.

Bazel's three existing collection suites still shared child PID `74897` and startup
token `7b2eca10-5eda-4dc0-a277-76505e3f13d0` across 22 passing cases on JDK 25.
Copied JDK 8 and JDK 21 logs, XML, the native Bazel logs, and its `summary.json` are
in `/tmp/rules-java-cancellation-proof`.

Bazel cancellation routing, the small baseline benchmark, and retained coverage
are now validated below. Parallel dispatch is deferred until multiplex persistent
test workers are supported. Cancellation capability remains explicitly opt-in.

## Bazel cancellation routing acceptance evidence

The actual Bazel interruption and timeout checks on 2026-10-09 passed. Interrupting
the hanging suite retired child PID `154075` in 1.02 seconds. Subsequent tests shared
replacement PID `155283` with dispatch counts 1 and 2. A two-second timeout was
reported as `TIMEOUT`, retired that child in 3.02 seconds including its remaining
timeout budget, and recovered with PID `157089` across two more passing dispatches.
Neither recovery created a new coordinator. Proof logs and XML are in
`/tmp/rules-java-routing-proof-final`.

All eighteen repository worker regressions passed, and Bazel's `WorkerTests` suite
passed all 244 cases, including deadline and sandboxed/unsandboxed timeout recovery
regressions. The native routing change is committed as `25a082a`.

## Classpath and service compatibility validation

The 2026-10-10 full Java-test attempt exposed omitted mock-fixture files, JDK
service discovery through the wrong loader, accumulated-jar suite discovery, stale
codec metadata, and the missing instrumentation append hook. Its timings are
diagnostic, because unrelated tests were discovered in retained JVMs.

After fixing these issues, 19 worker/runfiles regression targets passed. The 11
affected Bazel targets executed the same 1,656 cases in ordinary and persistent
modes, and all 11 targets passed in both modes after the final instrumentation
correction. The separate instrumentation acceptance run passed all 1,480 cases
in its five affected suites.
`SerializationTests`, `ExecTests`, `WorkerTests`, and `ActionsTests` shared child
PID `3249350`, token `edfc304b-a031-4801-84a0-b385bccb6a49`, across dispatches 1–4.
The root coverage acceptance test also passed four covered dispatches in one child
while retaining static state and separating each request's probes.

Evidence is saved in `/tmp/rules-java-fix-validation`, `/tmp/instrumentation-proof`,
`/tmp/rules-java-fixture-validation`, and `/tmp/bazel-codec-cache-proof`. Earlier full
profiles and execution logs are preserved in
`/tmp/rules-java-all-java-benchmark/diagnostic-before-fixes`.

## Small benchmark against ordinary Bazel

The 2026-10-09 benchmark used Bazel's `CollectionUtilsTest`, `ExtremaTest`, and
`ImmutableSharedKeyMapTest`: three short targets containing 22 passing test cases.
Both strategies used the same custom Bazel binary, JDK 25, local inputs, and serial
execution. Compilation and server warmup were excluded; result caching was disabled.
Three repetitions per strategy alternated execution order. The ordinary baseline
selected the normal Java launcher by disabling the persistent toolchain.

| Median over three runs | Ordinary Bazel | Warm persistent JVM | Reduction |
| --- | ---: | ---: | ---: |
| Whole command elapsed | 1.826 s | 1.125 s | 38.4% |
| Summed test execution from Bazel build events | 1.000 s | 0.108 s | 89.2% |

The cold persistent run took 1.719 s overall and 0.891 s in test execution. All
12 persistent dispatches, including that cold run, retained child PID `212246`
and startup token `a09d18e1-65eb-448d-9a87-ed5c1483d3f0`. This small sample provides
early feedback for these suites; it is not a general performance estimate.
Commands, build events, XML, logs, timings, and the measured binary's SHA-256 are in
`/tmp/rules-java-benchmark-precise`.

```sh
python3 test/java/bazel/rules/test_runner/reuse_benchmark.py \
  /absolute/custom/bazel /absolute/bazel/checkout \
  --output-base /absolute/existing/isolated/output-base \
  --artifacts /tmp/reuse-benchmark
```

## Retained coverage

`ReusableCoverage` uses the JaCoCo runtime in the test's own loader. It disables
shutdown-time dumping, resets probes at dispatch start, captures and resets them
at dispatch end, and invokes Bazel's coverage analyzer on only the current request's
jars. Each request writes unique `.exec` and `.dat` files in its own `COVERAGE_DIR`.
The driver reports collection failures as test failures. Coverage and ordinary
requests use separate child compatibility keys, while both retain application state.

The Bazel fork strips the single-shot coverage wrapper from worker startup, carries
coverage destinations as request environment, and retains Bazel's subsequent report
merging spawn. It cleans prior coverage intermediates before repeated execution.
The split merging wrapper no longer tries to recreate read-only metadata inputs.
These native changes are committed as `05e695e` in the Bazel checkout.

The coverage acceptance rig compares ordinary coverage with persistent coverage
and repeats the persistent run without test-result caching. Two repository targets
cover different methods of one retained class. Each report must cover its own
method and leave the other uncovered, while classloader identity and the application
static counter survive all four dispatches. Bazel's three collection suites share
one child across six covered dispatches; their non-initializer method hit counts
match ordinary execution. Every report contains covered source lines.

The 2026-10-09 proof retained repository child PID `347498` and Bazel child PID
`350369`. The initial Bazel reports covered 64, 28, and 74 lines respectively.
Repeated reports covered 64, 27, and 73 lines: class initialization does not execute
again in the warm loader. Copied reports, commands, logs, XML, and identities are in
`/tmp/rules-java-coverage-acceptance`. A final driver check also passed all three
Bazel suites with shared child PID `72627`; its copied reports and XML are in
that proof directory's `final-driver` subdirectory.

```sh
python3 test/java/bazel/rules/test_runner/coverage_reuse_test.py \
  /absolute/custom/bazel --artifacts /tmp/coverage-proof \
  --bazel-checkout /absolute/bazel/checkout \
  --bazel-output-base /absolute/existing/isolated/output-base
```

Omit the two Bazel checkout arguments to check only this repository. The rig selects
JDK 21 for repository tests and coverage tools because this repo's default tool
runtime is Java 8 and the current Bazel LCOV merger uses newer Java APIs. Bazel's
own suites use their configured JDK 25. `--bazel-action-path` can preserve a distinct
stable action PATH for that checkout.

`CoverageReuseTest` additionally drives First, Second, First directly through a
coordinator and checks isolated reports, retained state, and child cleanup. Its
Java 8 and Java 21 child assertions passed; the complete outer coverage command
passed with test and tool runtimes set to JDK 21 and Java-only collection selected.

The final repository regression run passed all eighteen worker and runfiles targets
on JDK 8 after the coverage changes. Bazel's 244 worker cases passed after the native
coverage routing change, and both acceptance scripts compile successfully.
