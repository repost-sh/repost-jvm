package sh.repost.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

/** Resolves native-engine artifacts exclusively through the active Maven repository session. */
final class MavenRepositoryArtifactSource implements MavenNativeEngineResolver.ArtifactSource {
    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    MavenRepositoryArtifactSource(
        RepositorySystem repositorySystem,
        RepositorySystemSession session,
        List<RemoteRepository> repositories
    ) {
        this.repositorySystem = Objects.requireNonNull(repositorySystem, "repositorySystem");
        this.session = Objects.requireNonNull(session, "session");
        this.repositories = Collections.unmodifiableList(new ArrayList<>(
            Objects.requireNonNull(repositories, "repositories")
        ));
    }

    @Override
    public Path resolve(MavenNativeEngineResolver.Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        Artifact artifact = new DefaultArtifact(
            "sh.repost",
            "repost-schema-engine",
            coordinate.classifier(),
            coordinate.extension(),
            coordinate.version()
        );
        ArtifactRequest request = new ArtifactRequest(artifact, repositories, "repost-native-engine");
        try {
            ArtifactResult result = repositorySystem.resolveArtifact(session, request);
            File file = result.getArtifact() == null ? null : result.getArtifact().getFile();
            if (!result.isResolved() || file == null) {
                throw failure(coordinate);
            }
            return file.toPath();
        } catch (ArtifactResolutionException exception) {
            throw failure(coordinate);
        }
    }

    Path cacheRoot() {
        if (session.getLocalRepository() == null || session.getLocalRepository().getBasedir() == null) {
            throw new MavenNativeEngineResolver.ResolutionException(
                "Maven local repository is unavailable for the Repost native engine cache"
            );
        }
        return session.getLocalRepository().getBasedir().toPath().resolve(".repost/native-engine");
    }

    private MavenNativeEngineResolver.ResolutionException failure(
        MavenNativeEngineResolver.Coordinate coordinate
    ) {
        String message = "could not resolve native engine artifact " + coordinate;
        if (session.isOffline()) {
            message += " from the Maven cache while offline; run Maven online once to populate this coordinate";
        }
        return new MavenNativeEngineResolver.ResolutionException(message);
    }
}
