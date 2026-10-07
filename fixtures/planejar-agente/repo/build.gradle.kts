plugins {
    id("org.springframework.boot") version "4.0.8" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(27) }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.8")
            mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
        }
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
