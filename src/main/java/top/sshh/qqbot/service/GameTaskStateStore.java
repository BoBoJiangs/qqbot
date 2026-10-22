package top.sshh.qqbot.service;

import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Component;
import top.sshh.qqbot.data.MiYuExploreContext;
import top.sshh.qqbot.service.storage.LocalDataFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/** 每个账号独立保存游戏计时和本轮秘域流程；逻辑文档实际写入现有 SQLite。 */
@Component
public class GameTaskStateStore {
    private final Path directory;

    public GameTaskStateStore() {
        this(Paths.get("./cache/game-task-state"));
    }

    GameTaskStateStore(Path directory) {
        this.directory = directory;
    }

    public synchronized State load(long botId) {
        Path path = directory.resolve(botId + ".json");
        try {
            if (!LocalDataFiles.exists(path)) return new State();
            State state = JSON.parseObject(LocalDataFiles.readString(path), State.class);
            if (state == null) throw new IOException("游戏任务记录为空：" + botId);
            return state;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void setMjTime(long botId, long time) {
        update(botId, state -> state.mjTime = time);
    }

    public synchronized void setXslTime(long botId, long time) {
        update(botId, state -> state.xslTime = time);
    }

    public synchronized void setMyTime(long botId, Long time) {
        update(botId, state -> {
            state.myTime = time;
            if (time != null && time > 0L) state.exploration = null;
        });
    }

    public synchronized void setExploration(long botId, MiYuExploreContext exploration) {
        update(botId, state -> state.exploration = exploration);
    }

    public synchronized void cancelAutomaticTasks(long botId) {
        update(botId, state -> {
            state.mjTime = -1L;
            state.xslTime = -1L;
            state.myTime = null;
            state.exploration = null;
        });
    }

    private void update(long botId, Consumer<State> change) {
        State state = load(botId);
        change.accept(state);
        try {
            LocalDataFiles.writeString(directory.resolve(botId + ".json"),
                    JSON.toJSONString(state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static class State {
        public Long mjTime;
        public Long xslTime;
        public Long myTime;
        public MiYuExploreContext exploration;
    }
}
