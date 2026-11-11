package com.aireader.infrastructure.storage;

import com.aireader.application.port.ImageStorage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class LocalImageStorage implements ImageStorage {
    private final Path root;
    public LocalImageStorage(Path directory) throws IOException {
        root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        // With one application instance, these can only be leftovers from an interrupted atomic save.
        try (var files = Files.newDirectoryStream(root, "*.tmp")) {
            for (Path file : files) {
                if (file.getFileName().toString().matches("[0-9a-f-]{36}[0-9]+\\.tmp")) Files.deleteIfExists(file);
            }
        }
    }
    private Path path(UUID id) { return root.resolve(id.toString() + ".image"); }

    public synchronized void save(UUID id, byte[] image) throws IOException {
        Path temporary = Files.createTempFile(root, id.toString(), ".tmp");
        try {
            Files.write(temporary, image);
            try { Files.move(temporary, path(id), StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, path(id)); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public byte[] get(UUID id) throws IOException { return Files.readAllBytes(path(id)); }
    public synchronized void delete(UUID id) throws IOException { Files.deleteIfExists(path(id)); }
    public synchronized Set<UUID> ids() throws IOException {
        var ids = new HashSet<UUID>();
        try (var files = Files.newDirectoryStream(root, "*.image")) {
            for (Path file : files) {
                try { ids.add(UUID.fromString(file.getFileName().toString().replace(".image", ""))); }
                catch (IllegalArgumentException ignored) { /* Not an object owned by this adapter. */ }
            }
        }
        return ids;
    }
}
