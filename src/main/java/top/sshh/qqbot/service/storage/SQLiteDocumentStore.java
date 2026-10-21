package top.sshh.qqbot.service.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.*;

/** 将原有配置文档存入 SQLite；旧路径只是稳定的数据键，不再是写入目标。 */
public final class SQLiteDocumentStore {
    private final String url;
    private final Path root;

    public SQLiteDocumentStore(Path database, Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Path target = database.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        url = "jdbc:sqlite:" + target + "?busy_timeout=10000&journal_mode=WAL&synchronous=FULL";
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS app_document ("
                    + "document_key TEXT PRIMARY KEY, content BLOB, updated_at INTEGER NOT NULL)");
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private String key(Path legacyPath) {
        Path absolute = legacyPath.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute).toString().replace('\\', '/');
    }

    /** 缺少文档时仅导入一次旧文件；已有数据（含删除标记）永远优先。 */
    private void importIfAbsent(Connection connection, Path path) throws SQLException, IOException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM app_document WHERE document_key = ?")) {
            query.setString(1, key(path));
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) return;
            }
        }
        if (!Files.isRegularFile(path)) return;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO app_document(document_key, content, updated_at) VALUES (?, ?, ?)")) {
            insert.setString(1, key(path));
            insert.setBytes(2, Files.readAllBytes(path));
            insert.setLong(3, Files.getLastModifiedTime(path).toMillis());
            insert.executeUpdate();
        }
    }

    public synchronized byte[] read(Path path) throws IOException {
        try (Connection connection = connect()) {
            importIfAbsent(connection, path);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT content FROM app_document WHERE document_key = ?")) {
                query.setString(1, key(path));
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        byte[] content = result.getBytes(1);
                        if (content != null) return content;
                    }
                }
            }
            throw new NoSuchFileException(path.toString());
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public synchronized boolean exists(Path path) throws IOException {
        try {
            read(path);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    public synchronized void write(Path path, byte[] content) throws IOException {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
                "INSERT INTO app_document(document_key, content, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(document_key) DO UPDATE SET content=excluded.content, "
                        + "updated_at=MAX(app_document.updated_at + 1, excluded.updated_at)")) {
            update.setString(1, key(path));
            update.setBytes(2, content);
            update.setLong(3, System.currentTimeMillis());
            update.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** 删除使用墓碑，避免重启后从保留的旧文件重新导入已删除数据。 */
    public synchronized boolean delete(Path path) throws IOException {
        boolean existed = exists(path);
        write(path, null);
        return existed;
    }

    public synchronized long lastModified(Path path) throws IOException {
        try (Connection connection = connect()) {
            importIfAbsent(connection, path);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT updated_at FROM app_document WHERE document_key = ? AND content IS NOT NULL")) {
                query.setString(1, key(path));
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) return result.getLong(1);
                }
            }
            throw new NoSuchFileException(path.toString());
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private IOException failure(SQLException e) {
        return new IOException("SQLite 本地数据读写失败", e);
    }
}
