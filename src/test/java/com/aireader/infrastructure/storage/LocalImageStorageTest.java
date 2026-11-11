package com.aireader.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class LocalImageStorageTest {
    @TempDir Path directory;
    @Test void survivesRecreationAndDeletesOnlyItsOwnObject() throws Exception {
        var id = UUID.randomUUID();
        var store = new LocalImageStorage(directory);
        store.save(id, new byte[]{1,2,3});
        Files.writeString(directory.resolve("keep.txt"), "unrelated");
        Path interrupted = directory.resolve(UUID.randomUUID() + "123.tmp");
        Files.write(interrupted, new byte[]{4,5,6});
        var reopened = new LocalImageStorage(directory);
        assertThat(interrupted).doesNotExist();
        assertThat(reopened.get(id)).containsExactly(1,2,3);
        assertThat(reopened.ids()).containsExactly(id);
        reopened.delete(id);
        assertThat(reopened.ids()).isEmpty();
        assertThat(directory.resolve("keep.txt")).exists();
        assertThatThrownBy(() -> reopened.get(id)).isInstanceOf(NoSuchFileException.class);
    }
}
