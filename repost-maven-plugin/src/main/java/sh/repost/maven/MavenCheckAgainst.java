package sh.repost.maven;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only comparison of isolated CHECK output against a marked checked-in tree. */
final class MavenCheckAgainst {
    private static final String MARKER = ".repost-output-root.json";

    private MavenCheckAgainst() {
    }

    static void compare(Path actualRoot, Path checkedInRoot) {
        Path expected = checkedInRoot.toAbsolutePath().normalize();
        Path marker = expected.resolve(MARKER);
        try {
            if (!Files.isDirectory(expected, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.size(marker) > 1024 * 1024
                || !Files.readString(marker).contains("\"formatVersion\": 1")) {
                throw new IllegalArgumentException(
                    "Repost checkAgainst must be a marked checked-in output root: " + expected
                );
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost checkAgainst marker could not be read");
        }
        Map<String, byte[]> actual = files(actualRoot, false);
        Map<String, byte[]> checkedIn = files(expected, true);
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : actual.entrySet()) {
            byte[] previous = checkedIn.get(entry.getKey());
            if (previous == null) {
                added.add(entry.getKey());
            } else if (!Arrays.equals(previous, entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (String path : checkedIn.keySet()) {
            if (!actual.containsKey(path)) {
                deleted.add(path);
            }
        }
        if (!added.isEmpty() || !changed.isEmpty() || !deleted.isEmpty()) {
            throw new IllegalArgumentException(
                "Repost checkAgainst differs\nadded: " + list(added)
                    + "\nchanged: " + list(changed)
                    + "\ndeleted: " + list(deleted)
            );
        }
    }

    private static String list(List<String> paths) {
        Collections.sort(paths);
        return paths.isEmpty() ? "[]" : "[" + String.join(", ", paths) + "]";
    }

    private static Map<String, byte[]> files(Path root, boolean omitRootMarker) {
        Path normalized = root.toAbsolutePath().normalize();
        Map<String, byte[]> result = new TreeMap<>();
        try {
            Files.walkFileTree(normalized, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()) {
                        throw new IllegalArgumentException("Repost checkAgainst tree contains a symbolic link");
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                        throw new IllegalArgumentException("Repost checkAgainst tree contains a non-regular file");
                    }
                    String relative = normalized.relativize(file).toString().replace('\\', '/');
                    if (!(omitRootMarker && MARKER.equals(relative))) {
                        result.put(relative, Files.readAllBytes(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Repost checkAgainst tree could not be enumerated");
        }
    }
}
