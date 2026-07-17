package sh.repost.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Prints the exact Repost Maven generation contract and common-path configuration. */
@Mojo(name = "help", requiresProject = false, threadSafe = true)
public final class RepostHelpMojo extends AbstractMojo {
    @Parameter(property = "detail", defaultValue = "false")
    private boolean detail;

    @Parameter(property = "goal")
    private String goal;

    /** Creates the Maven help goal. */
    public RepostHelpMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info(render(detail, goal));
    }

    static String render(boolean detailed, String selectedGoal) throws MojoExecutionException {
        String version = MavenPluginVersion.current();
        if (selectedGoal != null
            && !selectedGoal.isEmpty()
            && !"generate".equals(selectedGoal)
            && !"check".equals(selectedGoal)) {
            throw new MojoExecutionException("Unknown Repost Maven goal: " + selectedGoal);
        }
        if (!detailed) {
            return "Repost Maven Plugin " + version + "\n" +
                "Goals: repost:generate (generate-sources), repost:check (verify)\n" +
                "Run repost:help -Ddetail for configuration, ownership, and dependency guidance.";
        }
        String heading = selectedGoal == null || selectedGoal.isEmpty()
            ? "Goals: repost:generate (generate-sources), repost:check (verify)"
            : "Goal: repost:" + selectedGoal +
                ("generate".equals(selectedGoal) ? " (generate-sources)" : " (verify)");
        return "Repost Maven Plugin " + version + "\n" + heading + "\n\n" +
            "Required Java consumer dependencies:\n" +
            "BOM coordinate: sh.repost:repost-bom:" + version + "\n" +
            "<dependencyManagement>\n" +
            "  <dependencies>\n" +
            "    <dependency>\n" +
            "      <groupId>sh.repost</groupId>\n" +
            "      <artifactId>repost-bom</artifactId>\n" +
            "      <version>" + version + "</version>\n" +
            "      <type>pom</type>\n" +
            "      <scope>import</scope>\n" +
            "    </dependency>\n" +
            "  </dependencies>\n" +
            "</dependencyManagement>\n" +
            "<dependencies>\n" +
            "  <dependency>\n" +
            "    <groupId>sh.repost</groupId>\n" +
            "    <artifactId>repost-client</artifactId>\n" +
            "  </dependency>\n" +
            "</dependencies>\n" +
            "Kotlin replaces repost-client with repost-client-kotlin; the BOM supplies the version.\n\n" +
            "Kotlin runtime coordinate: sh.repost:repost-client-kotlin.\n\n" +
            "Configuration defaults:\n" +
            "  schemaMode=GENERATE (accepted: GENERATE, AGGREGATE_ONLY)\n" +
            "  schemaFile=${project.basedir}/repost/schema.repost\n" +
            "  generators=javaSdk,kotlinSdk\n" +
            "  sourceOutputDirectory=${project.build.directory}/generated-sources/repost/<generator-id>\n" +
            "  resourceOutputDirectory=${project.build.directory}/generated-resources/repost/<generator-id>\n" +
            "  environmentInputs={} (declare as <environmentInputs><NAME>value</NAME></environmentInputs>)\n" +
            "  integration=AUTO (accepted: AUTO, NONE, SPRING_BOOT, CDI)\n" +
            "  engineVersion=0.9.0\n" +
            "  executionTimeout=PT5M\n" +
            "  checkAgainst=(unset)\n\n" +
            "Environment inputs:\n" +
            "  <environmentInputs><NAME>value</NAME></environmentInputs>\n" +
            "  Only names declared in environmentInputs are available to env(NAME).\n" +
            "  Missing selected names fail generation; changing a declared value invalidates reuse.\n" +
            "  Ambient process environment is ignored and unrelated changes do not invalidate reuse.\n\n" +
            "Lifecycle and integration:\n" +
            "  generate binds to generate-sources; check binds to verify.\n" +
            "  AUTO inspects declared Maven dependencies, never runtime classes.\n" +
            "  Explicit integration must match one declared Repost adapter dependency.\n\n" +
            "Registry and ownership:\n" +
            "  GENERATE owns dedicated source/resource roots and emits the module registry.\n" +
            "  AGGREGATE_ONLY requires no local schema, consumes declared dependency registry resources,\n" +
            "  and emits one direct-reference application registry plus selected framework glue.\n" +
            "  Aggregate glue has separately owned source/resource roots.\n" +
            "  Shared src/main roots are rejected; use CLI generation plus checkAgainst for checked-in output.\n" +
            "  The plugin never adds or mutates project dependencies.\n" +
            "  Native engine artifacts resolve through Maven and cached offline runs require those exact coordinates.";
    }

    final void setDetail(boolean value) {
        detail = value;
    }

    final void setGoal(String value) {
        goal = value;
    }
}
