load("@io_bazel_rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")
load("@io_bazel_rules_scala//scala:scala.bzl", "scala_binary")

scala_toolchain(
    name = "diagnostics_reporter_toolchain_impl",
    enable_diagnostics_report = True,
    visibility = ["//visibility:public"],
)

toolchain(
    name = "diagnostics_reporter_toolchain",
    toolchain = "diagnostics_reporter_toolchain_impl",
    toolchain_type = "@io_bazel_rules_scala//scala:toolchain_type",
    visibility = ["//visibility:public"],
)

scala_binary(
    name = "main",
    srcs = glob(["*.scala"]),
    main_class = "Server",
    resources = ["akka.conf"],
    deps = [
        "@3p//:com_fasterxml_jackson_core_jackson_annotations",
        "@3p//:com_fasterxml_jackson_core_jackson_core",
        "@3p//:com_fasterxml_jackson_core_jackson_databind",
        "@3p//:com_fasterxml_jackson_module_jackson_module_scala_2_12",
        "@3p//:com_typesafe_akka_akka_actor_2_12",
        "@3p//:com_typesafe_akka_akka_actor_typed_2_12",
        "@3p//:org_slf4j_slf4j_api",
    ],
)
