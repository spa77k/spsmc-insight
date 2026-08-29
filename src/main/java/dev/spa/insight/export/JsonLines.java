package dev.spa.insight.export;

import dev.spa.insight.util.Json;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 1行1レコードのJSONL書き出し。AIにそのまま流し込めるよう、行の順序は書いた順のままにする。
 */
public class JsonLines implements Closeable {

    private final File file;
    private final BufferedWriter writer;
    private long lines;

    public JsonLines(File file) throws IOException {
        this.file = file;
        this.writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
    }

    public void write(Map<String, Object> row) throws IOException {
        writer.write(Json.write(row));
        writer.newLine();
        lines++;
    }

    public long lines() {
        return lines;
    }

    public File file() {
        return file;
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
