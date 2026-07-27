import de.undercouch.gradle.tasks.download.Download

plugins {
    id("triplea-java-library")
    alias(libs.plugins.download)
}

dependencies {
    implementation(project(":domain-data"))
    implementation(project(":game-core"))
    testImplementation(project(":game-headless"))
    testImplementation(project(":java-extras"))
    testImplementation(project(":test-common"))
}

tasks.named<Test>("test") {
    // AiGameTest is memory intensive due to ConcurrentBattleCalculator threads deserializing GameData concurrently.
    maxHeapSize = "2G"
}

// Batch headless PTV games for AI evaluation. Runs games sequentially: ProAi shares a static
// ConcurrentBattleCalculator across instances, so concurrent games in one JVM corrupt each other.
// For parallelism, launch multiple processes with disjoint --seeds.
tasks.register<JavaExec>("ptvEval") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs batch headless WW2 Path to Victory AI games and writes a results CSV"

    dependsOn(tasks.named("testClasses"))
    mainClass.set("org.triplea.ptv.eval.PtvEvalRunner")
    classpath = sourceSets["test"].runtimeClasspath
    // PtvEvalRunner resolves the map XML relative to the project directory.
    workingDir = projectDir
    maxHeapSize = (project.findProperty("ptvEvalHeap") as String?) ?: "6G"
    // ConcurrentBattleCalculator sizes its worker pool from Runtime.availableProcessors() and makes
    // one full GameData copy per worker, so an uncapped run will use every core on the machine.
    // -PptvEvalCores caps the JVM's view of the CPU count, keeping the machine usable during a run.
    (project.findProperty("ptvEvalCores") as String?)?.let { cores ->
        jvmArgs("-XX:ActiveProcessorCount=$cores")
    }
    // -PptvEvalAiLog=FINER captures ProAI's own decision log (purchase efficiencies, territory
    // prioritisation). FINE gives phase headers only; FINEST is unreadably large.
    (project.findProperty("ptvEvalAiLog") as String?)?.let { level ->
        jvmArgs("-Dtriplea.ai.pro.headlessLogLevel=$level")
    }
    args = (project.findProperty("ptvEvalArgs") as String?)?.split(" ")?.filter { it.isNotBlank() }
        ?: listOf("--games", "2", "--side", "axis", "--max-rounds", "20")
}

// Gather all URLs in the same game list file
val downloadURLs = mutableListOf<String>()
val saveGamesFile = project.layout.projectDirectory.file("save-game-list.txt").asFile
saveGamesFile.readLines().forEachIndexed { _, path ->
    if (!path.startsWith("#")) {
        downloadURLs.add(path)
    }
}

// Create a download task for the URLs
val downloadTask = tasks.register<Download>("downloadSaveGames") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Downloads save game files"

    src(downloadURLs)
    dest(project.layout.buildDirectory.dir("downloads/save-games"))
    overwrite(false)
    onlyIfModified(true)
    quiet(true)
}

// And wire test resource processing as a dependant on the download task
tasks.named<ProcessResources>("processTestResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(downloadTask) {
        into("save-games")
    }
}
