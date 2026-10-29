plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies { implementation(project(":core")) }

// A interface web é a mesma do celular
sourceSets["main"].resources.srcDir("../shared-assets")

application { mainClass.set("com.gestaoconfeitaria.desktop.MainKt") }

// ./gradlew :desktop:jarCompleto → desktop/build/libs/GestaoConfeitaria.jar (um arquivo só, roda com Java 17+)
tasks.register<Jar>("jarCompleto") {
    archiveFileName.set("GestaoConfeitaria.jar")
    manifest { attributes["Main-Class"] = "com.gestaoconfeitaria.desktop.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
}
