package com.musio.cli.process;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class ProjectRootResolver {
    public Path resolve() {
        return fromEnvironment()
                .or(this::fromCodeLocation)
                .or(() -> walkUp(Path.of("").toAbsolutePath()))
                .orElse(Path.of("").toAbsolutePath())
                .normalize();
    }

    private Optional<Path> fromEnvironment() {
        String configured = System.getenv("MUSIO_HOME");
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        Path home = Path.of(configured).toAbsolutePath().normalize();
        if (isProjectRoot(home) || isReleaseHome(home)) {
            return Optional.of(home);
        }
        return Optional.empty();
    }

    private Optional<Path> fromCodeLocation() {
        try {
            Path location = Path.of(ProjectRootResolver.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
                    .toAbsolutePath()
                    .normalize();
            if (Files.isRegularFile(location)) {
                return walkUp(location.getParent());
            }
            return walkUp(location);
        } catch (NullPointerException | URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<Path> walkUp(Path start) {
        Path current = start;
        while (current != null) {
            if (isProjectRoot(current) || isReleaseHome(current)) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    public static boolean isReleaseHome(Path path) {
        return Files.isRegularFile(path.resolve("dist").resolve("lib").resolve("musio-cli.jar"))
                && Files.isRegularFile(path.resolve("dist").resolve("app").resolve("backend-spring.jar"))
                && Files.isDirectory(path.resolve("dist")
                        .resolve("providers")
                        .resolve("qqmusic-python-sidecar")
                        .resolve("app"));
    }

    public static boolean isProjectRoot(Path path) {
        return Files.isDirectory(path.resolve("scripts"))
                && Files.isDirectory(path.resolve("backend-spring"))
                && Files.isDirectory(path.resolve("frontend"))
                && Files.isDirectory(path.resolve("providers").resolve("qqmusic-python-sidecar"));
    }
}
