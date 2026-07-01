// Root project — no code. Shared conventions live in each module's build script.
// All modules target the Java 25 toolchain (see gradle/libs.versions.toml).

tasks.register("printModules") {
    group = "help"
    description = "Lists the modules in this multi-module build."
    doLast {
        subprojects.forEach { println("• ${it.name}") }
    }
}
