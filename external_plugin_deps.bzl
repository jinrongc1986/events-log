load("//tools/bzl:maven_jar.bzl", "maven_jar")

def external_plugin_deps():
    maven_jar(
        name = "mockito",
        artifact = "org.mockito:mockito-core:2.21.0",
        sha1 = "cdd1d0d5b2edbd2a7040735ccf88318c031f458b",
        deps = [
            "@byte_buddy//jar",
            "@byte_buddy_agent//jar",
            "@objenesis//jar",
        ],
    )

    BYTE_BUDDY_VER = "1.8.15"

    maven_jar(
        name = "byte_buddy",
        artifact = "net.bytebuddy:byte-buddy:" + BYTE_BUDDY_VER,
        sha1 = "cb36fe3c70ead5fcd016856a7efff908402d86b8",
    )

    maven_jar(
        name = "byte_buddy_agent",
        artifact = "net.bytebuddy:byte-buddy-agent:" + BYTE_BUDDY_VER,
        sha1 = "a2dbe3457401f65ad4022617fbb3fc0e5f427c7d",
    )

    maven_jar(
        name = "objenesis",
        artifact = "org.objenesis:objenesis:2.6",
        sha1 = "639033469776fd37c08358c6b92a4761feb2af4b",
    )

    maven_jar(
        name = "hikaricp",
        artifact = "com.zaxxer:HikariCP:3.2.0",
        sha1 = "6c66db1c636ee90beb4c65fe34abd8ba9396bca6",
    )
