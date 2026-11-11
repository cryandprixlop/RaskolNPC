package ru.raskolnpc.core.database;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void closeIsIdempotentAndRejectsNewOperations() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(new File(temporaryDirectory.toFile(), "plugin-data"));
        DatabaseManager manager = new DatabaseManager(plugin, "test.db", 1);

        assertDoesNotThrow(manager::close);
        assertDoesNotThrow(manager::close);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> manager.initialize().join());
        assertTrue(failure.getCause().getMessage().contains("closed"));
    }
}
