// :core — saf Kotlin/JVM. android.* ve androidx.* importu YASAK (bkz. docs/ARCHITECTURE.md).
// Protokol motoru, transfer kuyruğu, arama/filtre ve paylaşım eşleştirme burada yaşar ve JVM'de test edilir.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Flow/StateFlow genel API'de görünür → api.
    // Not: tools/verify-core.sh coroutines 1.6.4 ile derler; 1.6.4'te olmayan API kullanılmaz.
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}

// Emülatör uçtan uca testi için demo Soulseek ağı (sahte sunucu + paylaşım yapan demo eşler, test kaynaklarında).
tasks.register<JavaExec>("demoNetwork") {
    group = "verification"
    description = "Runs the demo Soulseek network used by the emulator smoke test."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.soulseekplus.core.engine.DemoNetworkKt")
}

// CI: demo ağını Gradle daemon'u olmadan düz bir JVM'de çalıştırmak için test çalışma zamanı sınıf yolunu yazar.
tasks.register("writeDemoClasspath") {
    dependsOn("testClasses")
    val output = layout.buildDirectory.file("demo-classpath.txt")
    val classpath = sourceSets["test"].runtimeClasspath
    outputs.file(output)
    doLast { output.get().asFile.writeText(classpath.asPath) }
}
