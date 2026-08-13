pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "PaperReader-sources"
val sdkPath = providers.gradleProperty("paperReaderSdkPath")
    .orElse("PaperReader")
    .get()
val sdkDir = file(sdkPath)
if (sdkDir.resolve("extension-api").isDirectory) {
    includeBuild(sdkDir) { dependencySubstitution { substitute(module("dev.paperreader:extension-api")).using(project(":extension-api")) } }
}
include(":source-common", ":source-semanticscholar", ":source-crossref", ":source-arxiv", ":source-europepmc")
