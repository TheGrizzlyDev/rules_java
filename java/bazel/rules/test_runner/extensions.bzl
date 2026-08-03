"""Aspect that scans a test's deps for persistent-test-runner extensions."""

load("//java/private:java_info.bzl", "JavaInfo")

PersistentJvmTestRunnerExtensionInfo = provider(
    doc = "Per-target extension scan outputs, jar digests, and tool manifests.",
    fields = {
        "class_lists": "depset[File] — .extensions.txt files, one class name per line.",
        "digest_files": "depset[File] — .digest.txt files, one `path\\tsha256` line per jar.",
        "manifests": "depset[File] — per-target manifests linking a target's scans to the digest set that must be tracked as tools when a scan is non-empty.",
    },
)

_ATTRS_TO_TRAVERSE = ["deps", "runtime_deps", "exports"]

def _scan_extensions_aspect_impl(target, ctx):
    direct_class_lists = []
    direct_digest_files = []
    if JavaInfo in target:
        for jar in target[JavaInfo].runtime_output_jars:
            extensions_out = ctx.actions.declare_file(jar.basename + ".extensions.txt")
            digest_out = ctx.actions.declare_file(jar.basename + ".digest.txt")
            args = ctx.actions.args()
            args.use_param_file("@%s", use_always = True)
            args.set_param_file_format("multiline")
            args.add(jar)
            args.add(extensions_out)
            args.add(digest_out)
            ctx.actions.run(
                executable = ctx.executable._scanner,
                arguments = [args],
                inputs = [jar],
                outputs = [extensions_out, digest_out],
                mnemonic = "ScanJvmTestRunnerExtensions",
                execution_requirements = {
                    "supports-workers": "1",
                    "requires-worker-protocol": "proto",
                },
            )
            direct_class_lists.append(extensions_out)
            direct_digest_files.append(digest_out)

    transitive_class_lists = []
    transitive_digest_files = []
    transitive_manifests = []
    for attr_name in _ATTRS_TO_TRAVERSE:
        for dep in getattr(ctx.rule.attr, attr_name, None) or []:
            if PersistentJvmTestRunnerExtensionInfo in dep:
                info = dep[PersistentJvmTestRunnerExtensionInfo]
                transitive_class_lists.append(info.class_lists)
                transitive_digest_files.append(info.digest_files)
                transitive_manifests.append(info.manifests)

    class_lists = depset(direct = direct_class_lists, transitive = transitive_class_lists)
    digest_files = depset(direct = direct_digest_files, transitive = transitive_digest_files)

    direct_manifests = []
    if direct_class_lists or direct_digest_files:
        manifest = ctx.actions.declare_file(target.label.name + ".ext_manifest.txt")
        manifest_args = ctx.actions.args()
        manifest_args.set_param_file_format("multiline")
        manifest_args.add_all(direct_class_lists, format_each = "scan=%s")
        manifest_args.add_all(digest_files, format_each = "digest=%s")
        ctx.actions.write(manifest, manifest_args)
        direct_manifests.append(manifest)

    manifests = depset(direct = direct_manifests, transitive = transitive_manifests)

    return [
        PersistentJvmTestRunnerExtensionInfo(
            class_lists = class_lists,
            digest_files = digest_files,
            manifests = manifests,
        ),
        OutputGroupInfo(
            extensions_class_lists = class_lists,
            extensions_digest_files = digest_files,
            extensions_manifests = manifests,
        ),
    ]

scan_extensions_aspect = aspect(
    implementation = _scan_extensions_aspect_impl,
    attr_aspects = _ATTRS_TO_TRAVERSE,
    attrs = {
        "_scanner": attr.label(
            default = "//java/bazel/rules/test_runner:scan_jvm_test_runner_extensions",
            executable = True,
            cfg = "exec",
        ),
    },
)
