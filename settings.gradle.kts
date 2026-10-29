pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "GestaoConfeitaria"
include(":core")      // lógica compartilhada (Kotlin puro): banco, receitas, painel, Excel, sincronização
include(":app")       // Android
include(":desktop")   // computador (Windows, macOS, Linux)
