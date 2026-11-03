import java.net.URL
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.codex.carmusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.codex.carmusic"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("com.alphacephei:vosk-android:0.3.47")
}

val voskModelName = "vosk-model-small-en-us-0.15"
val voskModelUrl = "https://alphacephei.com/vosk/models/$voskModelName.zip"
val voskAssetsDir = layout.buildDirectory.dir("generated/voskAssets")
val prepareVoskModel by tasks.registering {
    outputs.dir(voskAssetsDir)
    doLast {
        val destination = voskAssetsDir.get().asFile
        val marker = destination.resolve("model/am/final.mdl")
        if (marker.isFile) return@doLast

        val archive = layout.buildDirectory.file("vosk/$voskModelName.zip").get().asFile
        archive.parentFile.mkdirs()
        if (!archive.isFile) {
            logger.lifecycle("Downloading offline Vosk model ($voskModelName)...")
            URL(voskModelUrl).openStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val modelRoot = destination.resolve("model")
        modelRoot.mkdirs()
        ZipFile(archive).use { zip ->
            val prefix = "$voskModelName/"
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val relative = entry.name.removePrefix(prefix)
                if (relative.isBlank()) continue
                val output = modelRoot.resolve(relative).canonicalFile
                require(output.toPath().startsWith(modelRoot.canonicalFile.toPath())) { "Unsafe path in Vosk model archive" }
                output.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { outputStream -> input.copyTo(outputStream) }
                }
            }
        }
        check(marker.isFile) { "Vosk model archive did not contain am/final.mdl" }
        val modelUuid = modelRoot.resolve("uuid")
        if (!modelUuid.isFile) modelUuid.writeText("vosk-model-small-en-us-0.15\n")
    }
}

android.sourceSets.getByName("main").assets.srcDir(voskAssetsDir)
tasks.named("preBuild").configure { dependsOn(prepareVoskModel) }
