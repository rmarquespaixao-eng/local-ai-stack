dependencies {
    implementation(project(":agent-core"))
    implementation("org.springframework.ai:spring-ai-client-chat")
    implementation("tools.jackson.core:jackson-databind")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
