package com.classaudio.recorder;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** 16-bit PCM WAV file. The header is rewritten every few seconds so a crash still leaves a playable file. */
final class WavWriter implements Closeable {
    static final int HEADER = 44;
    private final RandomAccessFile f;
    private final int rate, channels;
    private long dataBytes, headerAt;

    WavWriter(File file, int rate, int channels) throws IOException {
        this.rate = rate;
        this.channels = channels;
        f = new RandomAccessFile(file, "rw");
        f.setLength(0);
        writeHeader();
    }

    void write(byte[] b, int off, int len) throws IOException {
        f.write(b, off, len);
        dataBytes += len;
        if (dataBytes - headerAt > 5L * rate * channels * 2) writeHeader();
    }

    private void writeHeader() throws IOException {
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + dataBytes))
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) dataBytes);
        f.seek(0);
        f.write(h.array());
        f.seek(HEADER + dataBytes);
        headerAt = dataBytes;
    }

    @Override
    public void close() throws IOException {
        writeHeader();
        f.close();
    }
}
