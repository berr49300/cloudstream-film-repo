rootProject.name = "CloudStreamFilmRepo"

val excludedDirectories = setOf(
    ".git", ".github", ".gradle", "build", "gradle",
    "docs", "scripts", "tests"
)

rootDir.listFiles()
    ?.filter { it.isDirectory && it.name !in excludedDirectories }
    ?.filter { File(it, "build.gradle.kts").exists() }
    ?.forEach { include(it.name) }
