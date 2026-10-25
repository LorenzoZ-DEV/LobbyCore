plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.6"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "dev.lorenzzz"

version = "1.0.0b"

/*
 * Every repository declares the groups it actually hosts. Without the filters Gradle asks each
 * host about every artifact, and one slow mirror (repo.papermc.io regularly takes ~10s) stalls
 * resolution of dependencies it does not even serve.
 */
repositories {
    mavenCentral()

    maven("https://repo.papermc.io/repository/maven-public/") {          // Paper / Adventure
        content {
            includeGroupByRegex("io\\.papermc.*")
            includeGroupByRegex("com\\.destroystokyo.*")
            includeGroup("com.mojang")                                   // brigadier
            includeGroup("net.md-5")                                     // bungeecord-chat
            includeGroup("org.spigotmc")
        }
    }
    maven("https://repo.aikar.co/content/groups/aikar/") {               // ACF
        content { includeGroupByRegex("co\\.aikar.*") }
    }
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") {
        content { includeGroup("me.clip") }
    }
}

dependencies {
    // Provided by the server at runtime:
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // LuckPerms, for chat prefixes and tablist sorting weights. Soft dependency: LuckPermsHook
    // is the only class naming net.luckperms types and it is never touched without the plugin.
    compileOnly("net.luckperms:api:5.4")

    // Shaded into our jar, all relocated below:
    implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")                  // Aikar Commands
    implementation("com.zaxxer:HikariCP:5.1.0")                          // connection pool
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")       // caches
    implementation("redis.clients:jedis:5.2.0")                          // cross-lobby bus
    /*
     * FastBoard — packet-based sidebars. Worth a dependency rather than the usual Bukkit
     * scoreboard-team trick for two reasons: it does not flicker on update, and it never touches
     * the player's Scoreboard object. The second one is what matters here, because the tablist
     * module orders the player list with teams: a per-player Scoreboard for the sidebar would
     * wipe those teams every time the sidebar was rebuilt.
     */
    implementation("fr.mrmicky:fastboard:2.2.2")

    /*
     * JDBC drivers, shipped rather than borrowed — a lobby box rarely has them on the server
     * classpath, and StorageManager has no file fallback for the player store.
     *
     * Deliberately NOT relocated, unlike everything else. A JDBC driver is resolved by class
     * name (Class.forName, DriverManager, META-INF/services) and none of those follow a package
     * rename: a relocated driver is a driver nobody can find. Paper gives each plugin its own
     * class loader, so a server carrying its own copy does not collide with ours.
     */
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // protobuf is only needed by the X DevAPI, which nothing here uses, and it is another 2 MB.
    // A MariaDB server works through this driver too.
    implementation("com.mysql:mysql-connector-j:8.4.0") {
        exclude(group = "com.google.protobuf")
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")

    relocate("co.aikar.commands", "dev.lorenzzz.lobbycore.lib.acf")
    relocate("co.aikar.locales", "dev.lorenzzz.lobbycore.lib.locales")
    relocate("com.zaxxer.hikari", "dev.lorenzzz.lobbycore.lib.hikari")
    relocate("com.github.benmanes.caffeine", "dev.lorenzzz.lobbycore.lib.caffeine")
    relocate("redis.clients", "dev.lorenzzz.lobbycore.lib.jedis")
    relocate("fr.mrmicky.fastboard", "dev.lorenzzz.lobbycore.lib.fastboard")
    relocate("org.apache.commons.pool2", "dev.lorenzzz.lobbycore.lib.pool2")
    relocate("org.json", "dev.lorenzzz.lobbycore.lib.json")

    /*
     * sqlite-jdbc ships native libraries for two dozen platform/architecture pairs, 24 MB of
     * them, and a Minecraft server runs on exactly one. Keeping the four that can plausibly host
     * this plugin — x86_64 and arm64 Linux, musl x86_64 for Alpine containers, Windows x86_64 for
     * local testing — takes about twenty megabytes back off the jar. Drop the matching line if
     * the lobby ever moves to one of the excluded platforms.
     */
    exclude("org/sqlite/native/Mac/**")
    exclude("org/sqlite/native/FreeBSD/**")
    exclude("org/sqlite/native/Linux-Android/**")
    exclude("org/sqlite/native/Linux/x86/**")
    exclude("org/sqlite/native/Linux/arm/**")
    exclude("org/sqlite/native/Linux/armv6/**")
    exclude("org/sqlite/native/Linux/armv7/**")
    exclude("org/sqlite/native/Linux/ppc64/**")
    exclude("org/sqlite/native/Linux/riscv64/**")
    exclude("org/sqlite/native/Linux-Musl/x86/**")
    exclude("org/sqlite/native/Linux-Musl/aarch64/**")
    exclude("org/sqlite/native/Windows/x86/**")
    exclude("org/sqlite/native/Windows/armv7/**")
    exclude("org/sqlite/native/Windows/aarch64/**")

    // Both JDBC drivers register through java.sql.Driver; without this the last file copied
    // silently overwrites the other and one driver stops being discoverable.
    mergeServiceFiles()

    minimize {
        exclude(dependency("co.aikar:acf-paper:.*"))
        exclude(dependency("com.github.ben-manes.caffeine:caffeine:.*"))
        exclude(dependency("redis.clients:jedis:.*"))
        // Resolves NMS classes and packet fields by name at runtime; minimize sees no edge to
        // most of it and would strip exactly the parts reflection depends on.
        exclude(dependency("fr.mrmicky:fastboard:.*"))
        // Loaded by name and never referenced: minimize sees no edge to them and would strip them.
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        exclude(dependency("com.mysql:mysql-connector-j:.*"))
    }
}

tasks.assemble { dependsOn(tasks.shadowJar) }
tasks.build { dependsOn(tasks.shadowJar) }

tasks {
    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }
}
