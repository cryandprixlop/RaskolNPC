plugins {
    java
}

group = "ru.raskolnpc"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.glaremasters.me/repository/towny/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.135-stable")
    compileOnly("com.palmergames.bukkit.towny:towny:0.103.2.0")
    testImplementation("com.palmergames.bukkit.towny:towny:0.103.2.0")
    compileOnly("com.github.TownyAdvanced:SiegeWar:3.7.0")
    testImplementation("com.github.TownyAdvanced:SiegeWar:3.7.0")

    runtimeOnly("org.xerial:sqlite-jdbc:3.54.1.0")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.54.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.24.0")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.116.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
