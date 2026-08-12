plugins {
    application
    id("java")
    id("com.gradleup.shadow") version "9.6.1"
}

group = "net.ryanh"

// Release builds pass -PbutlerVersion=1.2.3, which reaches `butler --version` and
// ${butler.version} through the jar manifest.
version = (findProperty("butlerVersion") as String?) ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("info.picocli:picocli:4.7.7")

    implementation(platform("tools.jackson:jackson-bom:3.2.1"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("ch.qos.logback:logback-classic:1.6.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    mainClass.set("net.ryanh.butler.Main")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "butler",
            "Implementation-Version" to project.version,
            "Main-Class" to application.mainClass.get(),
        )
    }
}

// The shadow plugin already inherits the jar manifest; inheriting it again here is a cycle.
tasks.shadowJar {
    // The whole vocabulary arrives by ServiceLoader, so losing a META-INF/services file gives a jar
    // that starts and reports every uses: as unknown. Merging needs INCLUDE, or the duplicates are
    // dropped before the transformer sees them; append keeps the notices to one entry each.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    append("META-INF/LICENSE")
    append("META-INF/NOTICE")
}

tasks.test {
    useJUnitPlatform()
    // PackagingTest runs the shadow jar as a subprocess.
    dependsOn(tasks.shadowJar)
    systemProperty("butler.jar", tasks.shadowJar.flatMap { it.archiveFile }.get().asFile.path)
}
