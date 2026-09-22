plugins {
    application
}

repositories {
    mavenCentral()
}

val javafx = "21.0.8"
val platform = "linux"

dependencies {
    implementation("org.openjfx:javafx-base:$javafx:$platform")
    implementation("org.openjfx:javafx-graphics:$javafx:$platform")
    implementation("org.openjfx:javafx-controls:$javafx:$platform")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainModule = "sockscope"
    mainClass = "sockscope.App"
}

tasks.test {
    useJUnitPlatform()
}
