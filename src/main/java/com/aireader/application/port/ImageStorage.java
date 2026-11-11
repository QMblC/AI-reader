package com.aireader.application.port;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/** Opaque object IDs keep local paths and future S3 URLs out of Application and API. */
public interface ImageStorage {
    void save(UUID id, byte[] image) throws IOException;
    byte[] get(UUID id) throws IOException;
    void delete(UUID id) throws IOException;
    Set<UUID> ids() throws IOException;
}
