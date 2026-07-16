package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

final class RepostHelpMojoTest {
    @Test
    void detailedHelpOwnsTheExactCommonPathDefaultsAndSemantics() throws Exception {
        String help = RepostHelpMojo.render(true, null);

        assertTrue(help.contains("repost:generate (generate-sources)"));
        assertTrue(help.contains("repost:check (verify)"));
        assertTrue(help.contains("sh.repost:repost-bom:1.0.0"));
        assertTrue(help.contains("sh.repost:repost-client-kotlin"));
        assertTrue(help.contains("schemaMode=GENERATE (accepted: GENERATE, AGGREGATE_ONLY)"));
        assertTrue(help.contains("environmentInputs={} (declare as <environmentInputs><NAME>value</NAME>"));
        assertTrue(help.contains("integration=AUTO (accepted: AUTO, NONE, SPRING_BOOT, CDI)"));
        assertTrue(help.contains("engineVersion=0.9.0"));
        assertTrue(help.contains("executionTimeout=PT5M"));
        assertTrue(help.contains("checkAgainst=(unset)"));
        assertTrue(help.contains("declared dependency registry resources"));
        assertTrue(help.contains("Shared src/main roots are rejected"));
        assertTrue(help.contains("never adds or mutates project dependencies"));
        assertTrue(help.contains("<dependencyManagement>\n  <dependencies>"));
        assertTrue(help.contains("<artifactId>repost-bom</artifactId>"));
        assertTrue(help.contains("<scope>import</scope>"));
        assertTrue(help.contains("<artifactId>repost-client</artifactId>"));
        assertTrue(help.contains("Kotlin replaces repost-client with repost-client-kotlin"));
        assertTrue(help.contains("Only names declared in environmentInputs are available to env(NAME)"));
        assertTrue(help.contains("Ambient process environment is ignored"));
        assertTrue(help.contains("AUTO inspects declared Maven dependencies, never runtime classes"));
        assertTrue(help.contains("generate binds to generate-sources; check binds to verify"));
        assertTrue(help.contains("AGGREGATE_ONLY requires no local schema"));
        assertTrue(help.contains("Aggregate glue has separately owned source/resource roots"));
    }

    @Test
    void rejectsAnUnknownGoalWithoutEchoingConfiguration() {
        MojoExecutionException failure = assertThrows(
            MojoExecutionException.class,
            () -> RepostHelpMojo.render(true, "sentinel")
        );
        assertEquals("Unknown Repost Maven goal: sentinel", failure.getMessage());
    }
}
