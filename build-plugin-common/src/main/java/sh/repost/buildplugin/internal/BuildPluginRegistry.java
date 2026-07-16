package sh.repost.buildplugin.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Shared reflection-free client-registry reader and direct-reference renderer. */
public final class BuildPluginRegistry {
    private static final String CLIENT_PREFIX = "META-INF/repost/generated-clients/v1/";
    private static final String REGISTRY_PREFIX = "META-INF/repost/registries/v1/";
    private static final Pattern CLIENT_PATH = Pattern.compile(
        "META-INF/repost/generated-clients/v1/[0-9a-f]{16}/client\\.json"
    );
    private static final Pattern REGISTRY_PATH = Pattern.compile(
        "META-INF/repost/registries/v1/[0-9a-f]{16}/registry\\.json"
    );
    private static final Pattern ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern TYPE = Pattern.compile(
        "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+"
    );
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_CLIENTS = 1000;
    private static final List<String> CLIENT_FIELDS = Arrays.asList(
        "formatVersion", "generatorId", "language", "packageName", "clientType",
        "factoryType", "schemaHash", "descriptorVersion", "runtimeVersion"
    );
    private static final List<String> REGISTRY_FIELDS = Arrays.asList(
        "formatVersion", "registryId", "registryType", "clients"
    );

    private BuildPluginRegistry() {
    }

    /** Returns the generated-client languages declared by exact dependency registry resources. */
    public static Set<String> dependencyLanguages(Set<Path> dependencyArtifacts) {
        List<Client> clients = new ArrayList<>();
        Set<Path> artifacts = new TreeSet<>(Comparator.comparing(
            path -> path.toAbsolutePath().normalize().toString()
        ));
        artifacts.addAll(dependencyArtifacts);
        for (Path artifact : artifacts) {
            Path normalized = artifact.toAbsolutePath().normalize();
            readDependencyRegistries(artifact, normalized.toString(), clients);
        }
        Set<String> languages = new TreeSet<>();
        for (Client client : clients) {
            languages.add(client.language);
        }
        return Set.copyOf(languages);
    }

    /** Aggregates local client manifests and exact dependency registry resources. */
    public static void aggregate(Request request) {
        aggregate(request, BuildPluginRegistry::atomicMove);
    }

    static void aggregate(Request request, MoveOperation mover) {
        List<Client> clients = new ArrayList<>();
        for (Path localRoot : request.localResourceRoots) {
            readLocalClients(localRoot, clients);
        }
        int localClients = clients.size();
        Set<Path> artifacts = new TreeSet<>(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()));
        artifacts.addAll(request.dependencyArtifacts);
        for (Path artifact : artifacts) {
            Path normalized = artifact.toAbsolutePath().normalize();
            String identity = request.dependencyArtifactLabels.getOrDefault(
                normalized,
                normalized.toString()
            );
            readDependencyRegistries(artifact, identity + " [" + normalized + "]", clients);
        }
        if (request.aggregateOnly && clients.size() == localClients) {
            throw new IllegalArgumentException(
                "Repost AGGREGATE_ONLY requires at least one dependency registry resource; "
                    + "add a dependency on a generated Repost client module or set schemaMode=GENERATE"
            );
        }
        if (clients.isEmpty()) {
            replaceOutputs(request.sourceRoot, request.resourceRoot, null, null, mover);
            return;
        }
        if (clients.size() > MAX_CLIENTS) {
            throw new IllegalArgumentException("Repost application registry exceeds 1000 clients");
        }
        clients.sort(Comparator.comparing((Client client) -> client.clientType)
            .thenComparing(client -> client.generatorId));
        rejectDuplicates(clients);
        String registryType = request.aggregateOnly || clients.size() > localClients ? "application" : "module";
        String registryId = registryId(request, clients);
        String source = renderSource(registryId, clients);
        String resource = renderRegistry(registryId, registryType, clients);
        Outputs outputs = new Outputs(registryId, source, resource);
        if ("SPRING_BOOT".equals(request.integration)) {
            renderSpringBoot(registryId, clients, outputs);
        } else if ("CDI".equals(request.integration)) {
            renderCdi(registryId, clients, outputs);
        }
        replaceOutputs(request.sourceRoot, request.resourceRoot, registryId, outputs, mover);
    }

    private static void readLocalClients(Path root, List<Client> clients) {
        Path base = root.resolve(CLIENT_PREFIX).normalize();
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walk(base, 2)
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> CLIENT_PATH.matcher(root.relativize(path).toString().replace('\\', '/')).matches())
                .sorted()
                .forEach(path -> clients.add(parseClient(readBounded(path), path.toString())));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost local client manifests could not be enumerated");
        }
    }

    private static void readDependencyRegistries(Path artifact, String artifactLocation, List<Client> clients) {
        Path normalized = artifact.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Path base = normalized.resolve(REGISTRY_PREFIX).normalize();
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try {
                Files.walk(base, 2)
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> REGISTRY_PATH.matcher(
                        normalized.relativize(path).toString().replace('\\', '/')
                    ).matches())
                    .sorted()
                    .forEach(path -> parseRegistry(readBounded(path), artifactLocation + "!" +
                        normalized.relativize(path).toString().replace('\\', '/'), clients));
            } catch (IOException exception) {
                throw new IllegalArgumentException("Repost dependency registry resources could not be enumerated");
            }
            return;
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (JarFile jar = new JarFile(normalized.toFile(), false)) {
            Set<String> names = new HashSet<>();
            List<JarEntry> registries = new ArrayList<>();
            jar.stream().forEach(entry -> {
                if (!names.add(entry.getName())) {
                    throw new IllegalArgumentException(
                        "Repost dependency artifact has duplicate entry " + normalized + "!" + entry.getName()
                    );
                }
                if (!entry.isDirectory() && REGISTRY_PATH.matcher(entry.getName()).matches()) {
                    registries.add(entry);
                }
            });
            registries.sort(Comparator.comparing(JarEntry::getName));
            for (JarEntry entry : registries) {
                try (InputStream input = jar.getInputStream(entry)) {
                    parseRegistry(readBounded(input), artifactLocation + "!" + entry.getName(), clients);
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost dependency registry artifact could not be read: " + normalized);
        }
    }

    private static void parseRegistry(byte[] bytes, String location, List<Client> clients) {
        Map<String, Object> registry = object(StrictJson.parse(bytes), location);
        exactFields(registry, REGISTRY_FIELDS, location);
        exactInteger(registry.get("formatVersion"), 1, location + ".formatVersion");
        String registryId = matchingString(registry.get("registryId"), ID, location + ".registryId");
        String type = string(registry.get("registryType"), location + ".registryType");
        if (!"module".equals(type) && !"application".equals(type)) {
            throw new IllegalArgumentException(location + ".registryType is not supported");
        }
        Object value = registry.get("clients");
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(location + ".clients must be an array");
        }
        List<Client> registryClients = new ArrayList<>();
        for (Object item : (List<?>) value) {
            registryClients.add(parseClientObject(object(item, location + ".clients[]"), location));
        }
        String canonical = renderRegistry(registryId, type, registryClients);
        if (!Arrays.equals(bytes, canonical.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(location + " is not a canonical Repost registry manifest");
        }
        clients.addAll(registryClients);
    }

    private static Client parseClient(byte[] bytes, String location) {
        Client client = parseClientObject(object(StrictJson.parse(bytes), location), location);
        if (!Arrays.equals(bytes, renderClient(client, 0).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(location + " is not a canonical Repost client manifest");
        }
        return client;
    }

    private static Client parseClientObject(Map<String, Object> object, String location) {
        exactFields(object, CLIENT_FIELDS, location);
        exactInteger(object.get("formatVersion"), 1, location + ".formatVersion");
        String language = string(object.get("language"), location + ".language");
        if (!"java".equals(language) && !"kotlin".equals(language)) {
            throw new IllegalArgumentException(location + ".language is not supported");
        }
        exactInteger(object.get("descriptorVersion"), 2, location + ".descriptorVersion");
        String runtimeVersion = string(object.get("runtimeVersion"), location + ".runtimeVersion");
        if (!"1.0.0".equals(runtimeVersion)) {
            throw new IllegalArgumentException(location + " has incompatible runtimeVersion " + runtimeVersion);
        }
        return new Client(
            matchingString(object.get("generatorId"), ID, location + ".generatorId"),
            language,
            string(object.get("packageName"), location + ".packageName"),
            matchingString(object.get("clientType"), TYPE, location + ".clientType"),
            matchingString(object.get("factoryType"), TYPE, location + ".factoryType"),
            matchingString(object.get("schemaHash"), HASH, location + ".schemaHash"),
            runtimeVersion,
            location
        );
    }

    private static void rejectDuplicates(List<Client> clients) {
        Map<String, Client> identities = new HashMap<>();
        for (Client client : clients) {
            for (String identity : Arrays.asList(
                "generator " + client.generatorId,
                "client " + client.clientType,
                "factory " + client.factoryType
            )) {
                Client previous = identities.putIfAbsent(identity, client);
                if (previous != null) {
                    throw new IllegalArgumentException(
                        "duplicate Repost " + identity + " in " + previous.location + " and " + client.location
                    );
                }
            }
        }
    }

    private static String registryId(Request request, List<Client> clients) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        hashPart(digest, request.group);
        hashPart(digest, request.rootProjectName);
        hashPart(digest, request.projectPath);
        for (Client client : clients) {
            hashPart(digest, "1");
            hashPart(digest, client.generatorId);
            hashPart(digest, client.language);
            hashPart(digest, client.packageName);
            hashPart(digest, client.clientType);
            hashPart(digest, client.factoryType);
            hashPart(digest, client.schemaHash);
            hashPart(digest, "2");
            hashPart(digest, client.runtimeVersion);
        }
        return hex(digest.digest()).substring(0, 16);
    }

    private static void hashPart(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String renderSource(String registryId, List<Client> clients) {
        String packageName = "sh.repost.generated.r" + registryId;
        StringBuilder output = new StringBuilder();
        output.append("// Generated by Repost — do not edit.\n\n")
            .append("package ").append(packageName).append(";\n\n")
            .append("import java.util.ArrayList;\n")
            .append("import java.util.Collections;\n")
            .append("import java.util.List;\n")
            .append("import sh.repost.client.GeneratedRepostClientFactory;\n")
            .append("import sh.repost.client.GeneratedRepostClientRegistry;\n\n")
            .append("public final class RepostGeneratedClientRegistry implements GeneratedRepostClientRegistry {\n")
            .append("    public static final RepostGeneratedClientRegistry INSTANCE = new RepostGeneratedClientRegistry();\n")
            .append("    private static final List<GeneratedRepostClientFactory<?>> FACTORIES = createFactories();\n\n")
            .append("    private RepostGeneratedClientRegistry() {}\n\n")
            .append("    private static List<GeneratedRepostClientFactory<?>> createFactories() {\n")
            .append("        List<GeneratedRepostClientFactory<?>> factories = new ArrayList<>(")
            .append(clients.size()).append(");\n");
        int partCount = (clients.size() + 99) / 100;
        for (int part = 0; part < partCount; part++) {
            output.append("        addPart").append(part).append("(factories);\n");
        }
        output.append("        return Collections.unmodifiableList(factories);\n")
            .append("    }\n\n");
        for (int part = 0; part < partCount; part++) {
            output.append("    private static void addPart").append(part)
                .append("(List<GeneratedRepostClientFactory<?>> factories) {\n");
            int end = Math.min(clients.size(), (part + 1) * 100);
            for (int index = part * 100; index < end; index++) {
                output.append("        factories.add(").append(clients.get(index).factoryType)
                    .append(".INSTANCE);\n");
            }
            output.append("    }\n\n");
        }
        output
            .append("    @Override public List<GeneratedRepostClientFactory<?>> factories() { return FACTORIES; }\n")
            .append("}\n");
        return output.toString();
    }

    private static String renderRegistry(String registryId, String registryType, List<Client> clients) {
        StringBuilder output = new StringBuilder();
        output.append("{\n")
            .append("  \"formatVersion\": 1,\n")
            .append("  \"registryId\": ").append(json(registryId)).append(",\n")
            .append("  \"registryType\": ").append(json(registryType)).append(",\n")
            .append("  \"clients\": [\n");
        for (int index = 0; index < clients.size(); index++) {
            String rendered = renderClient(clients.get(index), 4);
            output.append(rendered, 0, rendered.length() - 1);
            output.append(index + 1 == clients.size() ? "\n" : ",\n");
        }
        return output.append("  ]\n}\n").toString();
    }

    private static void renderSpringBoot(String registryId, List<Client> clients, Outputs outputs) {
        String packageName = "sh.repost.generated.r" + registryId;
        StringBuilder source = new StringBuilder();
        source.append("// Generated by Repost — do not edit.\n\n")
            .append("package ").append(packageName).append(";\n\n")
            .append("import org.springframework.boot.autoconfigure.AutoConfiguration;\n")
            .append("import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;\n")
            .append("import org.springframework.context.annotation.Bean;\n")
            .append("import sh.repost.client.RepostRuntime;\n\n")
            .append("@AutoConfiguration\n")
            .append("public final class RepostGeneratedClientAutoConfiguration {\n")
            .append("    public RepostGeneratedClientAutoConfiguration() {}\n\n")
            .append("    @Bean\n")
            .append("    @ConditionalOnMissingBean(RepostGeneratedClientRegistry.class)\n")
            .append("    public RepostGeneratedClientRegistry repostGeneratedClientRegistry() {\n")
            .append("        return RepostGeneratedClientRegistry.INSTANCE;\n")
            .append("    }\n");
        Map<String, Integer> simpleNames = simpleNameCounts(clients);
        for (Client client : clients) {
            String simpleName = simpleName(client.clientType);
            String methodName = lowerCamel(simpleName);
            if (simpleNames.get(simpleName) > 1) {
                methodName += "_" + client.generatorId;
            }
            source.append("\n")
                .append("    @Bean\n")
                .append("    @ConditionalOnMissingBean(").append(client.clientType).append(".class)\n")
                .append("    public ").append(client.clientType).append(" ").append(methodName)
                .append("(RepostRuntime runtime) {\n")
                .append("        return ").append(client.factoryType).append(".INSTANCE.create(runtime);\n")
                .append("    }\n");
        }
        source.append("}\n");
        outputs.sources.put(
            packageName.replace('.', '/') + "/RepostGeneratedClientAutoConfiguration.java",
            source.toString()
        );
        outputs.resources.put(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            packageName + ".RepostGeneratedClientAutoConfiguration\n"
        );
    }

    private static void renderCdi(String registryId, List<Client> clients, Outputs outputs) {
        String packageName = "sh.repost.generated.r" + registryId;
        String packagePath = packageName.replace('.', '/');
        Map<String, Integer> simpleNames = simpleNameCounts(clients);
        StringBuilder extension = new StringBuilder();
        extension.append("// Generated by Repost — do not edit.\n\n")
            .append("package ").append(packageName).append(";\n\n")
            .append("import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;\n")
            .append("import jakarta.enterprise.inject.build.compatible.spi.Synthesis;\n")
            .append("import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;\n\n")
            .append("public final class RepostGeneratedClientBuildCompatibleExtension ")
            .append("implements BuildCompatibleExtension {\n")
            .append("    public RepostGeneratedClientBuildCompatibleExtension() {}\n\n")
            .append("    @Synthesis\n")
            .append("    public void synthesize(SyntheticComponents components) {\n");
        for (Client client : clients) {
            String creator = creatorName(client, simpleNames);
            extension.append("        components.addBean(").append(client.clientType).append(".class)\n")
                .append("            .type(").append(client.clientType).append(".class)\n")
                .append("            .scope(jakarta.inject.Singleton.class)\n")
                .append("            .createWith(").append(creator).append(".class);\n");
            outputs.sources.put(
                packagePath + "/" + creator + ".java",
                renderCdiCreator(packageName, creator, client)
            );
        }
        extension.append("    }\n}");
        outputs.sources.put(
            packagePath + "/RepostGeneratedClientBuildCompatibleExtension.java",
            extension.append('\n').toString()
        );
        outputs.resources.put(
            "META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension",
            packageName + ".RepostGeneratedClientBuildCompatibleExtension\n"
        );
    }

    private static String renderCdiCreator(String packageName, String creator, Client client) {
        return "// Generated by Repost — do not edit.\n\n"
            + "package " + packageName + ";\n\n"
            + "import jakarta.enterprise.inject.Instance;\n"
            + "import jakarta.enterprise.inject.build.compatible.spi.Parameters;\n"
            + "import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;\n"
            + "import sh.repost.client.RepostRuntime;\n\n"
            + "public final class " + creator + " implements SyntheticBeanCreator<"
            + client.clientType + "> {\n"
            + "    public " + creator + "() {}\n\n"
            + "    @Override\n"
            + "    public " + client.clientType + " create(Instance<Object> lookup, Parameters parameters) {\n"
            + "        RepostRuntime runtime = lookup.select(RepostRuntime.class).get();\n"
            + "        return " + client.factoryType + ".INSTANCE.create(runtime);\n"
            + "    }\n"
            + "}\n";
    }

    private static Map<String, Integer> simpleNameCounts(List<Client> clients) {
        Map<String, Integer> counts = new HashMap<>();
        for (Client client : clients) {
            counts.merge(simpleName(client.clientType), 1, Integer::sum);
        }
        return counts;
    }

    private static String creatorName(Client client, Map<String, Integer> simpleNames) {
        String simpleName = simpleName(client.clientType);
        return simpleNames.get(simpleName) == 1
            ? simpleName + "CdiCreator"
            : simpleName + "_" + client.generatorId + "CdiCreator";
    }

    private static String simpleName(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }

    private static String lowerCamel(String value) {
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String renderClient(Client client, int indent) {
        String prefix = " ".repeat(indent);
        String field = " ".repeat(indent + 2);
        return prefix + "{\n" +
            field + "\"formatVersion\": 1,\n" +
            field + "\"generatorId\": " + json(client.generatorId) + ",\n" +
            field + "\"language\": " + json(client.language) + ",\n" +
            field + "\"packageName\": " + json(client.packageName) + ",\n" +
            field + "\"clientType\": " + json(client.clientType) + ",\n" +
            field + "\"factoryType\": " + json(client.factoryType) + ",\n" +
            field + "\"schemaHash\": " + json(client.schemaHash) + ",\n" +
            field + "\"descriptorVersion\": 2,\n" +
            field + "\"runtimeVersion\": " + json(client.runtimeVersion) + "\n" +
            prefix + "}\n";
    }

    private static String json(String value) {
        StringBuilder output = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (current < 0x20) {
                        output.append(String.format("\\u%04x", (int) current));
                    } else {
                        output.append(current);
                    }
            }
        }
        return output.append('"').toString();
    }

    private static void replaceOutputs(
        Path sourceRoot,
        Path resourceRoot,
        String registryId,
        Outputs outputs,
        MoveOperation mover
    ) {
        Path sourceStage = sourceRoot.resolveSibling(sourceRoot.getFileName() + ".repost-stage");
        Path resourceStage = resourceRoot.resolveSibling(resourceRoot.getFileName() + ".repost-stage");
        Path sourceBackup = sourceRoot.resolveSibling(sourceRoot.getFileName() + ".repost-backup");
        Path resourceBackup = resourceRoot.resolveSibling(resourceRoot.getFileName() + ".repost-backup");
        Path journal = sourceRoot.resolveSibling(sourceRoot.getFileName() + ".repost-transaction");
        boolean sourcePublished = false;
        boolean resourcePublished = false;
        try {
            recoverTransaction(
                sourceRoot, resourceRoot, sourceBackup, resourceBackup, sourceStage, resourceStage, journal
            );
            deleteTree(sourceStage);
            deleteTree(resourceStage);
            deleteTree(sourceBackup);
            deleteTree(resourceBackup);
            if (outputs != null) {
                for (Map.Entry<String, String> output : outputs.sources.entrySet()) {
                    Path source = sourceStage.resolve(output.getKey());
                    Files.createDirectories(source.getParent());
                    Files.writeString(source, output.getValue(), StandardCharsets.UTF_8);
                }
                for (Map.Entry<String, String> output : outputs.resources.entrySet()) {
                    Path resource = resourceStage.resolve(output.getKey());
                    Files.createDirectories(resource.getParent());
                    Files.writeString(resource, output.getValue(), StandardCharsets.UTF_8);
                }
            } else {
                Files.createDirectories(sourceStage);
                Files.createDirectories(resourceStage);
            }
            Files.createDirectories(sourceRoot.getParent());
            Files.createDirectories(resourceRoot.getParent());
            writeJournal(
                journal,
                Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS),
                Files.exists(resourceRoot, LinkOption.NOFOLLOW_LINKS)
            );
            if (Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                mover.move(sourceRoot, sourceBackup);
            }
            if (Files.exists(resourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                mover.move(resourceRoot, resourceBackup);
            }
            mover.move(sourceStage, sourceRoot);
            sourcePublished = true;
            mover.move(resourceStage, resourceRoot);
            resourcePublished = true;
            Files.deleteIfExists(journal);
            deleteTree(sourceBackup);
            deleteTree(resourceBackup);
        } catch (IOException exception) {
            recoverTransaction(
                sourceRoot, resourceRoot, sourceBackup, resourceBackup, sourceStage, resourceStage, journal
            );
            throw new IllegalArgumentException("Repost aggregate registry outputs could not be replaced");
        } finally {
            deleteTree(sourceStage);
            deleteTree(resourceStage);
            if (sourcePublished && resourcePublished) {
                deleteTree(sourceBackup);
                deleteTree(resourceBackup);
            }
        }
    }

    private static void writeJournal(Path journal, boolean sourceOld, boolean resourceOld)
        throws IOException {
        Path temporary = journal.resolveSibling(journal.getFileName() + ".tmp");
        Files.writeString(
            temporary,
            "format=1\nsourceOld=" + sourceOld + "\nresourceOld=" + resourceOld + "\n",
            StandardCharsets.UTF_8
        );
        atomicMove(temporary, journal);
    }

    private static void recoverTransaction(
        Path sourceRoot,
        Path resourceRoot,
        Path sourceBackup,
        Path resourceBackup,
        Path sourceStage,
        Path resourceStage,
        Path journal
    ) {
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            String contents = Files.readString(journal, StandardCharsets.UTF_8);
            boolean sourceOld;
            boolean resourceOld;
            if ("format=1\nsourceOld=true\nresourceOld=true\n".equals(contents)) {
                sourceOld = true;
                resourceOld = true;
            } else if ("format=1\nsourceOld=true\nresourceOld=false\n".equals(contents)) {
                sourceOld = true;
                resourceOld = false;
            } else if ("format=1\nsourceOld=false\nresourceOld=true\n".equals(contents)) {
                sourceOld = false;
                resourceOld = true;
            } else if ("format=1\nsourceOld=false\nresourceOld=false\n".equals(contents)) {
                sourceOld = false;
                resourceOld = false;
            } else {
                throw new IOException("invalid registry transaction journal");
            }
            boolean sourceBackupExists = Files.exists(sourceBackup, LinkOption.NOFOLLOW_LINKS);
            boolean resourceBackupExists = Files.exists(resourceBackup, LinkOption.NOFOLLOW_LINKS);
            boolean allNew = Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS)
                && Files.exists(resourceRoot, LinkOption.NOFOLLOW_LINKS)
                && (!sourceOld || sourceBackupExists)
                && (!resourceOld || resourceBackupExists);
            if (allNew) {
                Files.deleteIfExists(journal);
                deleteTree(sourceBackup);
                deleteTree(resourceBackup);
            } else {
                restoreOld(sourceRoot, sourceBackup, sourceOld, sourceBackupExists);
                restoreOld(resourceRoot, resourceBackup, resourceOld, resourceBackupExists);
                Files.deleteIfExists(journal);
            }
            deleteTree(sourceStage);
            deleteTree(resourceStage);
        } catch (IOException | IllegalArgumentException recoveryFailure) {
            throw new IllegalArgumentException(
                "Repost aggregate registry transaction could not recover all-old or all-new outputs",
                recoveryFailure
            );
        }
    }

    private static void restoreOld(
        Path root,
        Path backup,
        boolean oldExisted,
        boolean backupExists
    ) throws IOException {
        if (backupExists) {
            deleteTree(root);
            atomicMove(backup, root);
        } else if (!oldExisted) {
            deleteTree(root);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        );
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target) throws IOException;
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost aggregate registry staging could not be cleaned");
        }
    }

    private static byte[] readBounded(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return readBounded(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost registry manifest could not be read: " + path);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_MANIFEST_BYTES) {
                throw new IllegalArgumentException("Repost registry manifest exceeds 1 MiB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String location) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(location + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static void exactFields(Map<String, Object> object, List<String> fields, String location) {
        if (!new ArrayList<>(object.keySet()).equals(fields)) {
            throw new IllegalArgumentException(location + " fields or canonical order do not match");
        }
    }

    private static void exactInteger(Object value, long expected, String location) {
        if (!(value instanceof Long) || ((Long) value).longValue() != expected) {
            throw new IllegalArgumentException(location + " does not match " + expected);
        }
    }

    private static String matchingString(Object value, Pattern pattern, String location) {
        String result = string(value, location);
        if (!pattern.matcher(result).matches()) {
            throw new IllegalArgumentException(location + " has invalid format");
        }
        return result;
    }

    private static String string(Object value, String location) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException(location + " must be a non-empty string");
        }
        return (String) value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format("%02x", value & 0xff));
        return output.toString();
    }

    /** Closed aggregation request. */
    public static final class Request {
        private final List<Path> localResourceRoots;
        private final Set<Path> dependencyArtifacts;
        private final Map<Path, String> dependencyArtifactLabels;
        private final Path sourceRoot;
        private final Path resourceRoot;
        private final String group;
        private final String rootProjectName;
        private final String projectPath;
        private final boolean aggregateOnly;
        private final String integration;

        /** Creates one Gradle registry aggregation request. */
        public Request(
            List<Path> localResourceRoots,
            Set<Path> dependencyArtifacts,
            Map<Path, String> dependencyArtifactLabels,
            Path sourceRoot,
            Path resourceRoot,
            String group,
            String rootProjectName,
            String projectPath,
            boolean aggregateOnly
        ) {
            this(
                localResourceRoots, dependencyArtifacts, dependencyArtifactLabels, sourceRoot,
                resourceRoot, group, rootProjectName, projectPath, aggregateOnly, "NONE"
            );
        }

        /** Creates one registry aggregation request with application framework glue. */
        public Request(
            List<Path> localResourceRoots,
            Set<Path> dependencyArtifacts,
            Map<Path, String> dependencyArtifactLabels,
            Path sourceRoot,
            Path resourceRoot,
            String group,
            String rootProjectName,
            String projectPath,
            boolean aggregateOnly,
            String integration
        ) {
            this.localResourceRoots = List.copyOf(localResourceRoots);
            this.dependencyArtifacts = Set.copyOf(dependencyArtifacts);
            Map<Path, String> labels = new HashMap<>();
            dependencyArtifactLabels.forEach((path, label) ->
                labels.put(path.toAbsolutePath().normalize(), label)
            );
            this.dependencyArtifactLabels = Map.copyOf(labels);
            this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
            this.resourceRoot = resourceRoot.toAbsolutePath().normalize();
            this.group = group;
            this.rootProjectName = rootProjectName;
            this.projectPath = projectPath;
            this.aggregateOnly = aggregateOnly;
            this.integration = integration;
        }
    }

    private static final class Client {
        private final String generatorId;
        private final String language;
        private final String packageName;
        private final String clientType;
        private final String factoryType;
        private final String schemaHash;
        private final String runtimeVersion;
        private final String location;

        private Client(
            String generatorId,
            String language,
            String packageName,
            String clientType,
            String factoryType,
            String schemaHash,
            String runtimeVersion,
            String location
        ) {
            this.generatorId = generatorId;
            this.language = language;
            this.packageName = packageName;
            this.clientType = clientType;
            this.factoryType = factoryType;
            this.schemaHash = schemaHash;
            this.runtimeVersion = runtimeVersion;
            this.location = location;
        }
    }

    private static final class Outputs {
        private final Map<String, String> sources = new java.util.TreeMap<>();
        private final Map<String, String> resources = new java.util.TreeMap<>();
        private Outputs(String registryId, String source, String resource) {
            sources.put(
                "sh/repost/generated/r" + registryId + "/RepostGeneratedClientRegistry.java",
                source
            );
            resources.put(REGISTRY_PREFIX + registryId + "/registry.json", resource);
        }
    }
}
