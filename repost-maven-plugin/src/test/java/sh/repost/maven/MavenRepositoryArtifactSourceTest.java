package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenRepositoryArtifactSourceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void usesTheMavenSessionAndExactArtifactRequest() throws Exception {
        Path resolved = Files.write(temporaryDirectory.resolve("engine.zip"), new byte[]{1});
        AtomicReference<ArtifactRequest> captured = new AtomicReference<>();
        RepositorySystem system = repositorySystem((session, request) -> {
            captured.set(request);
            return new ArtifactResult(request).setArtifact(request.getArtifact().setFile(resolved.toFile()));
        });
        RepositorySystemSession session = session(false);
        MavenRepositoryArtifactSource source = new MavenRepositoryArtifactSource(
            system,
            session,
            Collections.emptyList()
        );

        Path actual = source.resolve(new MavenNativeEngineResolver.Coordinate(
            "zip",
            "linux-x86_64",
            "0.9.0"
        ));

        assertEquals(resolved, actual);
        assertEquals("sh.repost", captured.get().getArtifact().getGroupId());
        assertEquals("repost-schema-engine", captured.get().getArtifact().getArtifactId());
        assertEquals("zip", captured.get().getArtifact().getExtension());
        assertEquals("linux-x86_64", captured.get().getArtifact().getClassifier());
        assertEquals("0.9.0", captured.get().getArtifact().getVersion());
        assertEquals(
            temporaryDirectory.resolve("repository/.repost/native-engine"),
            source.cacheRoot()
        );
    }

    @Test
    void offlineMissNamesTheExactCoordinateAndRemediation() {
        RepositorySystem system = repositorySystem((session, request) -> {
            ArtifactResult result = new ArtifactResult(request);
            throw new ArtifactResolutionException(Collections.singletonList(result));
        });
        MavenRepositoryArtifactSource source = new MavenRepositoryArtifactSource(
            system,
            session(true),
            Collections.emptyList()
        );

        MavenNativeEngineResolver.ResolutionException failure = assertThrows(
            MavenNativeEngineResolver.ResolutionException.class,
            () -> source.resolve(new MavenNativeEngineResolver.Coordinate(
                "json",
                "checksums",
                "0.9.0"
            ))
        );

        assertEquals(
            "could not resolve native engine artifact " +
                "sh.repost:repost-schema-engine:json:checksums:0.9.0 from the Maven cache while offline; " +
                "run Maven online once to populate this coordinate",
            failure.getMessage()
        );
    }

    private RepositorySystemSession session(boolean offline) {
        return (RepositorySystemSession) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{RepositorySystemSession.class},
            (proxy, method, arguments) -> {
                if ("isOffline".equals(method.getName())) {
                    return offline;
                }
                if ("getLocalRepository".equals(method.getName())) {
                    return new LocalRepository(temporaryDirectory.resolve("repository").toFile());
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private RepositorySystem repositorySystem(Resolver resolver) {
        return (RepositorySystem) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{RepositorySystem.class},
            (proxy, method, arguments) -> {
                if ("resolveArtifact".equals(method.getName())) {
                    return resolver.resolve(
                        (RepositorySystemSession) arguments[0],
                        (ArtifactRequest) arguments[1]
                    );
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    @FunctionalInterface
    private interface Resolver {
        ArtifactResult resolve(RepositorySystemSession session, ArtifactRequest request) throws Exception;
    }
}
