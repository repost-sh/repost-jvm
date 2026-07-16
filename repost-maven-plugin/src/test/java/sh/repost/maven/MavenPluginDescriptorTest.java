package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.aether.RepositorySystem;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class MavenPluginDescriptorTest {
    @Test
    void descriptorMatchesTheAnnotatedGoalsAndPinnedBuildToolBaselines() throws Exception {
        Document document;
        try (InputStream input = getClass().getResourceAsStream("/META-INF/maven/plugin.xml")) {
            assertNotNull(input);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            document = factory.newDocumentBuilder().parse(input);
        }

        Element plugin = document.getDocumentElement();
        assertEquals("sh.repost", text(plugin, "groupId"));
        assertEquals("repost-maven-plugin", text(plugin, "artifactId"));
        assertEquals("1.0.0", text(plugin, "version"));
        assertEquals("repost", text(plugin, "goalPrefix"));
        assertEquals("11", text(plugin, "requiredJavaVersion"));
        assertEquals("3.9.0", text(plugin, "requiredMavenVersion"));

        NodeList mojos = plugin.getElementsByTagName("mojo");
        assertEquals(3, mojos.getLength());
        assertGoal((Element) mojos.item(0), RepostCheckMojo.class, "check", "verify");
        assertGoal((Element) mojos.item(1), RepostGenerateMojo.class, "generate", "generate-sources");
        assertHelpGoal((Element) mojos.item(2));
    }

    private static void assertHelpGoal(Element descriptor) {
        assertEquals("help", text(descriptor, "goal"));
        assertEquals(RepostHelpMojo.class.getName(), text(descriptor, "implementation"));
        assertTrue(Boolean.parseBoolean(text(descriptor, "threadSafe")));
        Set<String> parameters = new HashSet<>();
        NodeList values = descriptor.getElementsByTagName("parameter");
        for (int index = 0; index < values.getLength(); index++) {
            parameters.add(text((Element) values.item(index), "name"));
        }
        assertEquals(Set.of("detail", "goal"), parameters);
    }

    private static void assertGoal(
        Element descriptor,
        Class<?> implementation,
        String goal,
        String phase
    ) {
        assertEquals(goal, text(descriptor, "goal"));
        assertEquals(phase, text(descriptor, "phase"));
        assertTrue(Boolean.parseBoolean(text(descriptor, "threadSafe")));
        assertEquals(implementation.getName(), text(descriptor, "implementation"));
        Set<String> parameters = new HashSet<>();
        NodeList values = descriptor.getElementsByTagName("parameter");
        for (int index = 0; index < values.getLength(); index++) {
            parameters.add(text((Element) values.item(index), "name"));
        }
        assertEquals(
            Set.of(
                "repositorySession", "remoteRepositories", "project", "schemaFile", "generators",
                "environmentInputs", "engineVersion", "executionTimeout", "schemaMode",
                "integration", "checkAgainst", "sourceOutputDirectory", "resourceOutputDirectory"
            ),
            parameters
        );
        assertTrue(text(descriptor, "requiresDependencyResolution").contains("compile"));
        Element configuration = (Element) descriptor.getElementsByTagName("configuration").item(0);
        assertEquals("${project.basedir}/repost/schema.repost",
            defaultValue(configuration, "schemaFile"));
        assertEquals("0.9.0", defaultValue(configuration, "engineVersion"));
        assertEquals("PT5M", defaultValue(configuration, "executionTimeout"));
        assertEquals("GENERATE", defaultValue(configuration, "schemaMode"));
        assertEquals("AUTO", defaultValue(configuration, "integration"));
        assertEquals("${repositorySystemSession}", defaultValue(configuration, "repositorySession"));
        assertEquals("${project.remoteProjectRepositories}",
            defaultValue(configuration, "remoteRepositories"));
        Element requirement = (Element) descriptor.getElementsByTagName("requirement").item(0);
        assertEquals(RepositorySystem.class.getName(), text(requirement, "role"));
        assertEquals("repositorySystem", text(requirement, "field-name"));
        assertTrue(parameterDescription(descriptor, "environmentInputs")
            .contains("ambient process environment is ignored"));
        assertTrue(parameterDescription(descriptor, "schemaMode")
            .contains("GENERATE or AGGREGATE_ONLY"));
        assertTrue(parameterDescription(descriptor, "integration")
            .contains("AUTO, NONE, SPRING_BOOT, or CDI"));
    }

    private static String defaultValue(Element configuration, String name) {
        return ((Element) configuration.getElementsByTagName(name).item(0))
            .getAttribute("default-value");
    }

    private static String parameterDescription(Element mojo, String parameterName) {
        NodeList values = mojo.getElementsByTagName("parameter");
        for (int index = 0; index < values.getLength(); index++) {
            Element parameter = (Element) values.item(index);
            if (parameterName.equals(text(parameter, "name"))) {
                return text(parameter, "description");
            }
        }
        throw new AssertionError("missing Maven parameter " + parameterName);
    }

    private static String text(Element parent, String name) {
        return parent.getElementsByTagName(name).item(0).getTextContent().trim();
    }
}
