package com.dettavo.common.file;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

@Component
public class AtomicFileWriter {

    public static final String TEMPORARY_SUFFIX = ".tmp";
    public static final String BACKUP_SUFFIX = ".bak";

    public void write(Path target, String content, boolean keepBackup) {
        write(target, content.getBytes(StandardCharsets.UTF_8), keepBackup);
    }

    public void write(Path target, byte[] content, boolean keepBackup) {
        Path temporary = target.resolveSibling(target.getFileName() + TEMPORARY_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            if (keepBackup && Files.exists(target)) {
                Path backup = target.resolveSibling(target.getFileName() + BACKUP_SUFFIX);
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The temporary file is removed on the next startup.
            }
            throw new UncheckedIOException("Cannot write " + target, e);
        }
    }
}
