package top.sshh.qqbot.service.storage;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 旧文件存储入口的兼容适配；读取时导入旧文件，写入只提交 SQLite。 */
public final class LocalDataFiles {
    private LocalDataFiles() { }

    public static Path databasePath() {
        String configured = System.getProperty("QQBOT_DATABASE", System.getenv("QQBOT_DATABASE"));
        return Paths.get(configured == null || configured.isBlank() ? "./config/qqbot.db" : configured);
    }

    private static class Holder {
        private static final SQLiteDocumentStore STORE = create();
        private static SQLiteDocumentStore create() {
            try {
                return new SQLiteDocumentStore(databasePath(), Paths.get("."));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static boolean exists(Path path, LinkOption... options) {
        try {
            return Holder.STORE.exists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] readAllBytes(Path path) throws IOException {
        return Holder.STORE.read(path);
    }

    public static String readString(Path path) throws IOException {
        return readString(path, StandardCharsets.UTF_8);
    }

    public static String readString(Path path, Charset charset) throws IOException {
        return new String(readAllBytes(path), charset);
    }

    public static BufferedReader newBufferedReader(Path path, Charset charset) throws IOException {
        return new BufferedReader(new StringReader(readString(path, charset)));
    }

    public static BufferedReader newBufferedReader(Path path) throws IOException {
        return newBufferedReader(path, StandardCharsets.UTF_8);
    }

    public static List<String> readAllLines(Path path, Charset charset) throws IOException {
        try (BufferedReader reader = newBufferedReader(path, charset)) {
            return reader.lines().collect(Collectors.toList());
        }
    }

    public static Path write(Path path, byte[] bytes, OpenOption... options) throws IOException {
        if (Arrays.asList(options).contains(StandardOpenOption.APPEND) && exists(path)) {
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            joined.write(readAllBytes(path));
            joined.write(bytes);
            bytes = joined.toByteArray();
        }
        Holder.STORE.write(path, bytes);
        return path;
    }

    public static Path write(Path path, Iterable<? extends CharSequence> lines, Charset charset,
            OpenOption... options) throws IOException {
        StringBuilder text = new StringBuilder();
        for (CharSequence line : lines) text.append(line).append('\n');
        return write(path, text.toString().getBytes(charset), options);
    }

    public static Path writeString(Path path, CharSequence content, Charset charset,
            OpenOption... options) throws IOException {
        return write(path, content.toString().getBytes(charset), options);
    }

    public static BufferedWriter newBufferedWriter(Path path, Charset charset, OpenOption... options)
            throws IOException {
        String initial = Arrays.asList(options).contains(StandardOpenOption.APPEND) && exists(path)
                ? readString(path, charset) : "";
        StringWriter buffer = new StringWriter();
        buffer.write(initial);
        return new BufferedWriter(buffer) {
            private boolean closed;
            @Override
            public void close() throws IOException {
                if (closed) return;
                super.close();
                Holder.STORE.write(path, buffer.toString().getBytes(charset));
                closed = true;
            }
        };
    }

    public static BufferedWriter newBufferedWriter(Path path, OpenOption... options) throws IOException {
        return newBufferedWriter(path, StandardCharsets.UTF_8, options);
    }

    public static InputStream newInputStream(Path path) throws IOException {
        return new ByteArrayInputStream(readAllBytes(path));
    }

    public static OutputStream newOutputStream(Path path) {
        return new ByteArrayOutputStream() {
            private boolean closed;
            @Override
            public void close() throws IOException {
                if (closed) return;
                Holder.STORE.write(path, toByteArray());
                closed = true;
                super.close();
            }
        };
    }

    public static FileTime getLastModifiedTime(Path path) throws IOException {
        return FileTime.fromMillis(Holder.STORE.lastModified(path));
    }

    public static boolean deleteIfExists(Path path) throws IOException {
        return Holder.STORE.delete(path);
    }

    /** QQ 群文件上传仍需要物理文件，只在导出时生成最新内容。 */
    public static Path export(Path path) throws IOException {
        byte[] content = readAllBytes(path);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.write(path, content);
        return path;
    }
}
