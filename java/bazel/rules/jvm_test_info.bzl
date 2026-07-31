"""Info rule for the JVM-test toolchain."""

def _jvm_persistent_test_runner_info_impl(ctx):
    return [
        platform_common.ToolchainInfo(
            runner = ctx.attr.runner,
            additional_deps = ctx.attr.additional_deps,
        ),
    ]

jvm_persistent_test_runner_info = rule(
    implementation = _jvm_persistent_test_runner_info_impl,
    doc = "Author-facing rule that produces the JVM-test toolchain info.",
    attrs = {
        "runner": attr.label(
            executable = True,
            cfg = "exec",
            mandatory = True,
        ),
        "additional_deps": attr.label_list(),
    },
)
