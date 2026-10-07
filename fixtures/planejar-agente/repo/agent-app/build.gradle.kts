apply(plugin = "org.springframework.boot")

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":agent-spring-ai"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configure<org.springframework.boot.gradle.dsl.SpringBootExtension> {
    mainClass = "dev.agente.app.AgentApplication"
}
