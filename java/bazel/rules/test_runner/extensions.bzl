"""Aspect that scans a test's deps for persistent-test-runner extensions."""

load("//java/private:java_info.bzl", "JavaInfo")

PersistentJvmTestRunnerExtensionInfo = provider(
    doc = "Per-target list of extension class names discovered in that target's jars.",
    fields = {
        "class_lists": "depset[File] — .txt files, one class name per line.",
    },
)

_ATTRS_TO_TRAVERSE = ["deps", "runtime_deps", "exports"]

def _scan_extensions_aspect_impl(target, ctx):
    direct = []
    if JavaInfo in target:
        for jar in target[JavaInfo].runtime_output_jars:
            out = ctx.actions.declare_file(jar.basename + ".extensions.txt")
            args = ctx.actions.args()
            args.use_param_file("@%s", use_always = True)
            args.set_param_file_format("multiline")
            args.add(jar)
            args.add(out)
            ctx.actions.run(
                executable = ctx.executable._scanner,
                arguments = [args],
                inputs = [jar],
                outputs = [out],
                mnemonic = "ScanJvmTestRunnerExtensions",
                execution_requirements = {
                    "supports-workers": "1",
                    "requires-worker-protocol": "proto",
                },
            )
            direct.append(out)

    transitive = []
    for attr_name in _ATTRS_TO_TRAVERSE:
        for dep in getattr(ctx.rule.attr, attr_name, None) or []:
            if PersistentJvmTestRunnerExtensionInfo in dep:
                transitive.append(dep[PersistentJvmTestRunnerExtensionInfo].class_lists)

    class_lists = depset(direct = direct, transitive = transitive)
    return [
        PersistentJvmTestRunnerExtensionInfo(class_lists = class_lists),
        OutputGroupInfo(extensions_class_lists = class_lists),
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
