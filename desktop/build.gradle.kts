import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.animation)
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

compose.desktop {
    application {
        mainClass = "com.drcine.pc.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "DRcine"
            packageVersion = "1.0.0"
            description = "DRcine para PC"
            vendor = "DRcine"
            // jdk.crypto.ec: sem ele o HTTPS falha no programa instalado. Os outros evitam erro de classe faltando.
            modules("java.naming", "java.sql", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                menuGroup = "DRcine"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                iconFile.set(project.file("icons/icon.ico"))
                upgradeUuid = "6f1c2a4e-3b7d-4c59-9a1e-d2c8b0f4a7e1"
            }
        }
    }
}
