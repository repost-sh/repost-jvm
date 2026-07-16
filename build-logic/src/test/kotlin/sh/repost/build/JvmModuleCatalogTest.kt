package sh.repost.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JvmModuleCatalogTest {
    @Test
    fun `catalog contains the complete product and fixture topology`() {
        assertEquals(14, JvmModuleCatalog.modules.size)
        assertEquals(7, JvmModuleCatalog.publicLibraryModules.size)
        assertEquals(
            JvmModuleCatalog.modules.keys,
            JvmModuleCatalog.modules.values.map(JvmModuleSpec::name).toSet(),
        )
        assertFalse(JvmModuleCatalog.modules.containsKey("repost-client-quarkus"))
        assertFalse(JvmModuleCatalog.modules.containsKey("repost-client-quarkus-deployment"))
        assertFalse(JvmModuleCatalog.modules.containsKey("repost-client-micronaut"))
    }

    @Test
    fun `published coordinates and module names are unique`() {
        val coordinates = JvmModuleCatalog.modules.values.mapNotNull(JvmModuleSpec::publishedCoordinate)
        val moduleNames = JvmModuleCatalog.modules.values.mapNotNull(JvmModuleSpec::automaticModuleName)
        assertEquals(coordinates.size, coordinates.toSet().size)
        assertEquals(moduleNames.size, moduleNames.toSet().size)
    }

    @Test
    fun `core points nowhere and engine is never a project dependency`() {
        assertTrue(JvmModuleCatalog.modules.getValue("repost-client").allowedProjectDependencies.isEmpty())
        assertFalse(JvmModuleCatalog.modules.values.any { "repost-http-engine" in it.allowedProjectDependencies })
        assertNull(JvmModuleCatalog.modules.getValue("repost-http-engine").publishedCoordinate)
    }

    @Test
    fun `core and integrations have separate bytecode baselines`() {
        val coreReleases = JvmModuleCatalog.modules.values
            .filter { it.kind in setOf(JvmModuleKind.JAVA_LIBRARY, JvmModuleKind.KOTLIN_LIBRARY) }
            .mapNotNull(JvmModuleSpec::javaRelease)
            .toSet()
        val integrationReleases = JvmModuleCatalog.modules.values
            .filter { it.kind == JvmModuleKind.JAVA_INTEGRATION }
            .mapNotNull(JvmModuleSpec::javaRelease)
            .toSet()
        assertEquals(setOf(11), coreReleases)
        assertEquals(setOf(17), integrationReleases)
    }

    @Test
    fun `public Gradle plugin identity is frozen`() {
        assertEquals(
            "sh.repost.sdk",
            JvmModuleCatalog.modules.getValue("repost-gradle-plugin").gradlePluginId,
        )
    }

    @Test
    fun `only unsealed artifacts remain explicitly unimplemented`() {
        val sealedArtifacts = setOf(
            "repost-client",
            "repost-client-kotlin",
            "repost-client-test",
            "repost-client-micrometer",
            "repost-client-opentelemetry",
            "repost-client-spring-boot-starter",
            "repost-client-cdi",
            "repost-maven-plugin",
            "repost-gradle-plugin",
        )
        assertEquals(
            JvmModuleCatalog.modules.values
                .filter { it.javaRelease != null }
                .map(JvmModuleSpec::name)
                .toSet() - sealedArtifacts,
            JvmModuleCatalog.unimplementedArtifactModules,
        )
        assertEquals(
            sealedArtifacts,
            JvmModuleCatalog.modules.values
                .filter(JvmModuleSpec::implementedArtifact)
                .map(JvmModuleSpec::name)
                .toSet(),
        )
    }
}
