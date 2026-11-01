plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Lógica comum do DRcine (addons, catálogo, servidores). Sem nada de Android: é a mesma do app do celular.
kotlin { jvmToolchain(17) }

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // No Android o org.json já vem no sistema; no PC ele precisa vir junto.
    api("org.json:json:20240303")
}
