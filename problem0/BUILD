load("@io_bazel_rules_scala//scala:scala.bzl", "scala_binary")

scala_binary(
    name = "main",
    srcs = glob(["*.scala"]),
    main_class = "Server",
    deps = [
        "@3p//:com_fasterxml_jackson_core_jackson_core",
        "@3p//:com_typesafe_akka_akka_actor_2_12",
    ],
)
