package com.dettavo.feature.transcription;

import com.dettavo.feature.media.ClipFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A clip's cache/audio16k.wav: 16-bit PCM, as AudioExtractionService writes it. Chunks are cut from it in Java
 * (guide 6.1): it is plain PCM, so a byte range plus a new 44-byte header is a valid WAV file.
 *
 * @param dataOffset where the samples start in the file
 * @param dataBytes  how many bytes of samples follow
 */
public record WavAudio(int sampleRate, int channels, int bitsPerSample, long dataOffset, long dataBytes) {

    private static final int PCM = 1;
    private static final int HEADER_BYTES = 44;
    private static final long UNKNOWN_SIZE = 0xFFFFFFFFL;

    /** Reads the header. Fails if the file is not 16-bit PCM WAV. */
    public static WavAudio read(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer riff = bytes(channel, 0, 12);
            if (!"RIFF".equals(ascii(riff, 0)) || !"WAVE".equals(ascii(riff, 8))) {
                throw new IllegalStateException(file + " is not a WAV file");
            }
            Integer format = null;
            int channels = 0;
            int sampleRate = 0;
            int bits = 0;
            long position = 12;
            while (position + 8 <= channel.size()) {
                ByteBuffer header = bytes(channel, position, 8);
                String id = ascii(header, 0);
                long size = Integer.toUnsignedLong(header.getInt(4));
                long body = position + 8;
                if (id.equals("fmt ")) {
                    ByteBuffer fmt = bytes(channel, body, 16);
                    format = (int) fmt.getShort(0);
                    channels = fmt.getShort(2);
                    sampleRate = fmt.getInt(4);
                    bits = fmt.getShort(14);
                } else if (id.equals("data")) {
                    if (format == null || format != PCM || bits != 16 || channels < 1) {
                        throw new IllegalStateException(file + " is not 16-bit PCM audio");
                    }
                    long available = channel.size() - body;
                    long dataBytes = size == 0 || size == UNKNOWN_SIZE ? available : Math.min(size, available);
                    return new WavAudio(sampleRate, channels, bits, body, dataBytes);
                }
                position = body + size + (size & 1);
            }
            throw new IllegalStateException(file + " has no audio data");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    public int blockAlign() {
        return channels * bitsPerSample / 8;
    }

    public long durationMs() {
        return dataBytes * 1000 / ((long) sampleRate * blockAlign());
    }

    /** The byte offset of a time inside the samples, on a whole frame. */
    public long byteOffsetOf(long ms) {
        long offset = ms * sampleRate / 1000 * blockAlign();
        return Math.clamp(offset, 0, dataBytes);
    }

    /** Writes [startMs, endMs) of source to target as its own WAV file, through a .partial file. */
    public void writeSlice(Path source, long startMs, long endMs, Path target) {
        long from = byteOffsetOf(startMs);
        long length = byteOffsetOf(endMs) - from;
        ClipFiles.createDirectories(target.getParent());
        Path partial = ClipFiles.partialOf(target);
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer header = header(length);
            while (header.hasRemaining()) {
                out.write(header);
            }
            long copied = 0;
            while (copied < length) {
                long moved = in.transferTo(dataOffset + from + copied, length - copied, out);
                if (moved <= 0) {
                    throw new IOException("unexpected end of " + source);
                }
                copied += moved;
            }
        } catch (IOException e) {
            ClipFiles.discard(partial);
            throw new UncheckedIOException("Cannot write " + target, e);
        }
        ClipFiles.commit(partial, target);
    }

    private ByteBuffer header(long dataLength) {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt((int) (36 + dataLength));
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) PCM);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(sampleRate * blockAlign());
        header.putShort((short) blockAlign());
        header.putShort((short) bitsPerSample);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt((int) dataLength);
        return header.flip();
    }

    private static ByteBuffer bytes(FileChannel channel, long position, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new IOException("unexpected end of file");
            }
        }
        return buffer.flip();
    }

    private static String ascii(ByteBuffer buffer, int index) {
        byte[] id = new byte[4];
        buffer.get(index, id);
        return new String(id, StandardCharsets.US_ASCII);
    }
}
