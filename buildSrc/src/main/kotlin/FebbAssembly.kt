import abstractor.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonObject
import metautils.testing.getResources
import metautils.testing.verifyClassFiles
import metautils.util.*
import net.fabricmc.mapping.tree.TinyMappingFactory
import net.fabricmc.mapping.tree.TinyTree
import net.fabricmc.stitch.merge.JarMerger
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class FebbAssembly : Plugin<Project> {
    companion object {
        lateinit var artifacts: ProjectContext
    }

    override fun apply(project: Project) {
        ProjectContext(project).apply {
            artifacts = this
            apply()
        }
    }
}


private val abstractedClasses = setOf(
        "net/minecraft/block/Block",
        "net/minecraft/entity/Entity",
        "net/minecraft/world/World"
)

private val baseClassClasses = setOf(
        "net/minecraft/block/Block",
        "net/minecraft/entity/Entity"
)


class ProjectContext(private val project: Project) {
    private val sourceSets = project.convention.getPlugin(JavaPluginConvention::class.java).sourceSets
    private val resourcesOutputDir = sourceSets.getByName("main").output.resourcesDir!!
    private val classesOutputDir = sourceSets.getByName("main").output.classesDirs.first()
//    final SourceSetContainer sourceSets = javaPlugin.getSourceSets();

    private val mcVersion = project.property("minecraft_version").toString()
    private val mappingsBuild = project.property("mappings_build").toString().toInt()
    private val febbDir = project.buildDir.resolve("febbAssembly").toPath()
    private val versionDir = febbDir.resolve(mcVersion)
    private val minecraftDir = versionDir.resolve("minecraft")
    private val clientPath = minecraftDir.resolve("client.jar")
    private val serverPath = minecraftDir.resolve("server.jar")
    private val mergedPath = minecraftDir.resolve("merged.jar")
    private val remappedMcPath = minecraftDir.resolve("named.jar")
    private val libsDir = versionDir.resolve("libraries")
    private val mappingsDir = versionDir.resolve("mappings")
    private val mappingsJar = mappingsDir.resolve("yarn-build.$mappingsBuild.jar")
    private val mappingsPath = mappingsDir.resolve("yarn-build.$mappingsBuild.tinyv2")
    private val abstractedDir = versionDir.resolve("abstracted")
    private val abstractedDirectOutputDir = abstractedDir.resolve("directOutput")
    private val apiBinariesDir = abstractedDirectOutputDir.resolve("api")
    private val apiSourcesDir = abstractedDirectOutputDir.resolve("api-sources")

    private val abstractionManifestJson = abstractedDirectOutputDir.resolve("abstractionManifest.json")

    private val implNamedDir = abstractedDirectOutputDir.resolve("impl-named")

    private val runtimeManifestProperties = resourcesOutputDir.resolve("runtimeManifest.properties").toPath()
    private val implNamedDest = classesOutputDir.toPath()
    private val currentVersionAbstractedDirInClasses = implNamedDest.resolve("v" + mcVersion.replace(".","_"))

    private val apiForDevTesting = project.file("dev/test-api.jar").toPath()
    val apiBinariesJar = abstractedDir.resolve("api.jar")
    val apiSourcesJar = abstractedDir.resolve("api-sources.jar")

    val abstractionManifestJar = abstractedDir.resolve("abstractionManifest.jar")


    private fun downloadIfChanged(url: String, path: Path) {
        DownloadUtil.downloadIfChanged(URL(url), path.toFile(), project.logger)
    }

    fun apply() {
//        JavaPlugin.CLASSES_TASK_NAME
//        val x: Classes
        val abstractTask = project.task("Abstract") { task ->
            task.group = "FebbAssembly"

            task.inputs.properties(project.properties
                    .filterKeys { it in setOf("minecraft_version", "mappings_build", "api_build") })
            task.outputs.dir(abstractedDir.toFile())
            task.outputs.dir(currentVersionAbstractedDirInClasses.toFile())
            task.outputs.file(runtimeManifestProperties.toFile())
            task.outputs.file(apiForDevTesting.toFile())
            task.doLast {
                val versionManifest = Minecraft.downloadVersionManifest(
                        Minecraft.downloadVersionManifestList(), mcVersion
                )
                downloadMinecraft(versionManifest)
                downloadMcLibraries(versionManifest)
                mergeMinecraftJars()
                downloadMappings()
                val classpath = libsDir.recursiveChildren().filter { it.hasExtension(".jar") }.toList()
                val mappings = Files.newBufferedReader(mappingsPath).use { TinyMappingFactory.load(it) }
                remapMinecraftJar(classpath, mappings)

                abstractMinecraft(classpath, mappings)
                copyImplToClassesDir()
                copyApiForTestingInDev()
            }
        }

        project.tasks.getByName("processResources").dependsOn(abstractTask)
        project.tasks.getByName("classes").dependsOn(abstractTask)
    }

    private fun copyApiForTestingInDev() {
        apiForDevTesting.createParentDirectories()
        apiBinariesJar.copyTo(apiForDevTesting)
    }

    private fun copyImplToClassesDir() {
        classesOutputDir.deleteRecursively()
//        currentVersionAbstractedDirInClasses.deleteRecursively()
        FileUtils.copyDirectory(implNamedDir.toFile(), implNamedDest.toFile())
    }

    private fun downloadMinecraft(versionManifest: JsonObject) {
        val downloads = versionManifest.getObject("downloads")
        val client = downloads.getObject("client").getPrimitive("url").content
        val server = downloads.getObject("server").getPrimitive("url").content
        minecraftDir.createDirectories()
        downloadIfChanged(client, clientPath)
        downloadIfChanged(server, serverPath)
    }

    private fun downloadMcLibraries(versionManifest: JsonObject) {
        for (library in Minecraft.getLibraryUrls(versionManifest)) {
            val path = libsDir.resolve(library.filePath)
            path.createParentDirectories()
            downloadIfChanged(library.url, path)
        }
    }

    private fun mergeMinecraftJars() {
        JarMerger(clientPath.toFile(), serverPath.toFile(), mergedPath.toFile()).use { jarMerger ->
            jarMerger.enableSyntheticParamsOffset()
            jarMerger.merge()
        }
    }

    private fun downloadMappings() {
        mappingsDir.createDirectories()
        downloadIfChanged(Fabric.getMergedMappingsUrl(mcVersion, mappingsBuild), mappingsJar)
        mappingsJar.openJar {
            it.getPath("mappings/mappings.tiny").copyTo(mappingsPath)
        }
    }

    private fun remapMinecraftJar(classpath: List<Path>, mappings: TinyTree) {
        remap(
                mappings,
                classpath,
                fromNamespace = "official",
                toNamespace = "named",
                fromPath = mergedPath,
                toPath = remappedMcPath
        )
    }

//    private fun remapImplJar(classpath: List<Path>, mappings: TinyTree) {
//        remap(
//            mappings,
//            classpath,
//            fromNamespace = "named",
//            toNamespace = "intermediary",
//            fromPath = implNamedJar,
//            toPath = implIntJar
//        )
//    }

    private fun remap(
            mappings: TinyTree,
            classpath: List<Path>,
            fromNamespace: String,
            toNamespace: String,
            fromPath: Path,
            toPath: Path
    ) {
        val remapper = TinyRemapper.newRemapper()
                .withMappings(TinyRemapperMappingsHelper.create(mappings, fromNamespace, toNamespace, true))
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .build()

        OutputConsumerPath.Builder(toPath).build().use { outputConsumer ->
            outputConsumer.addNonClassFiles(fromPath)
            remapper.readClassPath(*classpath.toTypedArray())
            remapper.readInputs(fromPath)
            remapper.apply(outputConsumer)
        }
    }

    private fun abstractMinecraft(
            classpath: List<Path>,
            mappings: TinyTree
    ) {
        assert(remappedMcPath.exists())
        val metadata = createAbstractionMetadata(classpath)
        val manifest = runAbstractor(metadata, classpath)
        saveManifest(manifest, mappings)
    }

    private fun createAbstractionMetadata(classpath: List<Path>): AbstractionMetadata {
        return getResources("interfaces.conf", "baseclasses.conf") { interfaces, baseclasses ->
            val interfaceSelection = AbstractionSelection.fromHocon(interfaces.readToString())
            val baseclassSelection = AbstractionSelection.fromHocon(baseclasses.readToString())
             AbstractionMetadata(
                    versionPackage = VersionPackage.fromMcVersion(mcVersion),
                    writeRawAsm = true,
                    fitToPublicApi = false,
                    classPath = classpath,
                    javadocs = JavaDocs.readTiny(mappingsPath),
                    selector = AbstractionSelections(interfaceSelection, baseclassSelection).toTargetSelector()
            )
        }


    }

    private fun runAbstractor(
            metadata: AbstractionMetadata,
            classpath: List<Path>
    ): AbstractionManifest {
        abstractedDirectOutputDir.createDirectories()
        val manifest = Abstractor.parse(mcJar = remappedMcPath, metadata = metadata) {
            it.abstract(implNamedDir, metadata)
            val apiMetadata = metadata.copy(fitToPublicApi = true)
            it.abstract(apiBinariesDir, apiMetadata)
            it.abstract(apiSourcesDir, apiMetadata.copy(writeRawAsm = false))
        }

        verifyClassFiles(implNamedDir, classpath + listOf(remappedMcPath))
//        implNamedDir.convertDirToJar(implNamedJar)
        apiBinariesDir.convertDirToJar(apiBinariesJar)
        apiSourcesDir.convertDirToJar(apiSourcesJar)
        return manifest
    }

    private fun saveManifest(manifest: AbstractionManifest, mappings: TinyTree) {
        abstractionManifestJson.writeString(
                Json(JsonConfiguration.Stable).stringify(AbstractionManifestSerializer, manifest)
        )

        abstractionManifestJson.storeInJar(abstractionManifestJar)

        val namedToInt = mappings.mapNamedClassesToIntermediary()

        val intDotQualifiedToApi = manifest.map { (mcClassName, apiClassInfo) ->
            namedToInt.getValue(mcClassName).replace("/", ".") to apiClassInfo.apiClassName
        }

        Properties().apply {
            putAll(intDotQualifiedToApi)
            Files.newOutputStream(runtimeManifestProperties).use { store(it, null) }
        }

//        runtimeManifestProperties.storeInJar(runtimeManifestJar)

    }

    private fun Path.storeInJar(jarPath: Path) {
        jarPath.deleteIfExists()
        jarPath.createJar()
        jarPath.openJar {
            copyTo(it.getPath("/$fileName"))
        }
    }

}
