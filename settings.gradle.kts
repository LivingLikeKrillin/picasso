rootProject.name = "picasso"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include("contracts", "profile-model", "gate", "mimic")
