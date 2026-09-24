plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
}

group = "dev.reeldeal"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper dropped the {version}-R0.1-SNAPSHOT scheme as of 26.1 in favor of
    // build-numbered coordinates. `.+` resolves the latest published 26.2 build;
    // pin to an exact one (e.g. "26.2.build.42") once you want reproducible builds.
    paperweight.paperDevBundle("26.2.build.+")
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
}
