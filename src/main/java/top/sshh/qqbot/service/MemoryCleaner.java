//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package top.sshh.qqbot.service;

import com.sun.management.OperatingSystemMXBean;
import com.zhuangxv.bot.annotation.GroupMessageHandler;
import com.zhuangxv.bot.core.Bot;
import com.zhuangxv.bot.core.Group;
import com.zhuangxv.bot.core.Member;
import com.zhuangxv.bot.core.component.BotFactory;
import com.zhuangxv.bot.message.MessageChain;
import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemoryCleaner {
    private static final Logger logger = LoggerFactory.getLogger(MemoryCleaner.class);
    private static final double MEMORY_THRESHOLD = 0.8;
    private static final long CLEANUP_INTERVAL_MS = 1800000L;
    private static final long INITIAL_DELAY_MS = 60000L;
    private static final int DIRECT_MEMORY_SIZE = 1048576;
    @Autowired
    private GroupManager groupManager;
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    public MemoryCleaner() {

    }

    private String getSystemStatus() {
        OperatingSystemMXBean osBean = (OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        long days = uptime / 86400000L;
        long hours = uptime % 86400000L / 3600000L;
        long minutes = uptime % 3600000L / 60000L;
        long seconds = uptime % 60000L / 1000L;
        return String.format("系统版本：%s %s\nCPU核心数：%d个\n系统CPU：%.1f%%\n进程CPU：%.1f%%\n%s\n%s\n%s\n运行时间：%d天%d小时%d分%d秒", System.getProperty("os.name"), System.getProperty("os.version"), osBean.getAvailableProcessors(), osBean.getSystemCpuLoad() * 100.0, osBean.getProcessCpuLoad() * 100.0, this.getHeapMemoryUsage(), this.getProcessMemoryUsage(), this.getSystemMemoryUsage(), days, hours, minutes, seconds);
    }

    private String getHeapMemoryUsage() {
        MemoryUsage heapUsage = this.memoryMXBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        double ratio = (double)used / (double)max;
        return String.format("堆内存：%.1f%% \n(已用 %s / 最大 %s)", ratio * 100.0, this.formatSize(used), this.formatSize(max));
    }

    @Scheduled(
            fixedRate = 21600000L,
            initialDelay = 600000L
    )
    public void autoCleanMessageCache() {
        logger.info("开始自动清理聊天记录缓存");
        Map<Long, Bot> bots = BotFactory.getBots();
        if (bots != null && !bots.isEmpty()) {
            int totalRemoved = 0;
            Iterator var3 = bots.values().iterator();

            while(var3.hasNext()) {
                Bot bot = (Bot)var3.next();

                try {
                    int removedCount = bot.cleanCacheMessageChain(100);
                    totalRemoved += removedCount;
                    logger.info("Bot({}) 清理了 {} 条聊天记录", bot.getBotId(), removedCount);
                } catch (Exception var6) {
                    Exception e = var6;
                    logger.error("清理Bot(" + bot.getBotId() + ")的聊天记录缓存时出错", e);
                }
            }

            logger.info("完成聊天记录缓存清理，共清理了 {} 条记录", totalRemoved);
        } else {
            logger.info("没有找到任何Bot实例");
        }
    }

    private String getProcessMemoryUsage() {
        try {
            long rss = 0L;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("linux")) {
                Path statusPath = Paths.get("/proc/self/status");
                Iterator var5 = Files.readAllLines(statusPath).iterator();

                while(var5.hasNext()) {
                    String line = (String)var5.next();
                    if (line.startsWith("VmRSS:")) {
                        String[] parts = line.split("\\s+");
                        rss = Long.parseLong(parts[1]) * 1024L;
                        break;
                    }
                }
            } else if (os.contains("win")) {
                try {
                    String jvmName = ManagementFactory.getRuntimeMXBean().getName();
                    long pid = Long.parseLong(jvmName.split("@")[0]);
                    Process process = Runtime.getRuntime().exec("cmd /c tasklist /FI \"PID eq " + pid + "\" /NH");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));

                    String line;
                    try {
                        while((line = reader.readLine()) != null) {
                            if (!line.trim().isEmpty()) {
                                String[] parts = line.trim().split("\\s+");
                                if (parts.length >= 5 && parts[1].equals(String.valueOf(pid))) {
                                    String memStr = parts[4].replaceAll(",", "");
                                    rss = Long.parseLong(memStr) * 1024L;
                                    break;
                                }
                            }
                        }
                    } catch (Throwable var13) {

                    }

                    reader.close();
                    if (process.waitFor() != 0) {
                        logger.warn("tasklist命令执行失败");
                    }
                } catch (Exception var14) {
                    Exception e = var14;
                    logger.warn("Windows内存获取失败", e);
                }
            }

            return String.format("进程内存：%s", this.formatSize(rss));
        } catch (Exception var15) {
            Exception e = var15;
            logger.warn("获取进程内存失败", e);
            return "进程内存：获取失败";
        }
    }

    private String getSystemMemoryUsage() {
        try {
            OperatingSystemMXBean osBean = (OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
            String os = System.getProperty("os.name").toLowerCase();
            long total = osBean.getTotalPhysicalMemorySize();
            long free = osBean.getFreePhysicalMemorySize();
            if (os.contains("linux")) {
                Path meminfoPath = Paths.get("/proc/meminfo");
                List<String> lines = Files.readAllLines(meminfoPath);
                long memTotal = 0L;
                long memFree = 0L;
                long buffers = 0L;
                long cached = 0L;
                long memAvailable = 0L;
                Iterator var19 = lines.iterator();

                while(var19.hasNext()) {
                    String line = (String)var19.next();
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String key = parts[0].replace(":", "");
                        long value = Long.parseLong(parts[1]) * 1024L;
                        switch (key) {
                            case "MemTotal":
                                memTotal = value;
                                break;
                            case "MemFree":
                                memFree = value;
                                break;
                            case "Buffers":
                                buffers = value;
                                break;
                            case "Cached":
                                cached = value;
                                break;
                            case "MemAvailable":
                                memAvailable = value;
                        }
                    }
                }

                long available = memAvailable > 0L ? memAvailable : memFree + buffers + cached;
                long used = memTotal - available;
                double ratio = (double)used / (double)memTotal;
                return String.format("系统内存：%.1f%% \n(已用 %s / 总计 %s)", ratio * 100.0, this.formatSize(used), this.formatSize(memTotal));
            } else {
                long used = total - free;
                double ratio = (double)used / (double)total;
                return String.format("系统内存：%.1f%% \n(已用 %s / 总计 %s)", ratio * 100.0, this.formatSize(used), this.formatSize(total));
            }
        } catch (Exception var27) {
            Exception e = var27;
            logger.warn("获取系统内存失败", e);
            return "系统内存：获取失败";
        }
    }

    private String formatSize(long bytes) {
        if (bytes > 1073741824L) {
            return String.format("%.2f GB", (double)bytes / 1.073741824E9);
        } else if (bytes > 1048576L) {
            return String.format("%.2f MB", (double)bytes / 1048576.0);
        } else {
            return bytes > 1024L ? String.format("%.2f KB", (double)bytes / 1024.0) : bytes + " B";
        }
    }

    private boolean handleRestart(long groupId, Bot bot) {
        try {
            String scriptPath = this.generateRestartScript(groupId);
            this.executeRestartScript(scriptPath);
            System.exit(0);
            return true;
        } catch (Exception var5) {
            Exception e = var5;
            logger.error("重启处理失败", e);
            return false;
        }
    }

    private String generateRestartScript(long groupId) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String scriptName = os.contains("win") ? "restart_bot.bat" : "restart_bot.sh";
        Path scriptPath = Paths.get(System.getProperty("user.dir"), scriptName);
        if (!Files.exists(scriptPath, new LinkOption[0])) {
            String scriptContent;
            if (os.contains("win")) {
                scriptContent = "@echo off\nset SCRIPT_DIR=%~dp0\ntaskkill /F /IM java.exe /FI \"WINDOWTITLE eq bot.jar\"\ntimeout 5 > nul\nset TZ=Asia/Shanghai\nstart \"\" javaw -Dfile.encoding=UTF-8 -jar \"%SCRIPT_DIR%bot.jar\" --spring.config.location=file:./config/\n";
            } else {
                scriptContent = "#!/bin/bash\nSCRIPT_DIR=$(cd \"$(dirname \"$0\")\" && pwd)\ncd \"$SCRIPT_DIR\" || exit 1\n\nlog() {\n  echo \"[$(date '+%Y-%m-%d %H:%M:%S')] $1\" >> \"$SCRIPT_DIR/restart.log\"\n}\n\n: > \"$SCRIPT_DIR/restart.log\"\nlog \"=== 开始重启流程 ===\"\n\nOLD_PID=$(pgrep -f \"java.*-jar $SCRIPT_DIR/bot.jar\")\nlog \"检测到旧进程PID: $OLD_PID\"\n\nif [ -n \"$OLD_PID\" ]; then\n  log \"发送SIGTERM到进程 $OLD_PID\"\n  kill \"$OLD_PID\"\n  \n  for i in {1..15}; do\n    if ! ps -p \"$OLD_PID\" > /dev/null; then\n      log \"进程 $OLD_PID 已正常退出\"\n      break\n    fi\n    sleep 1\n    log \"等待进程退出 ($i/15)...\"\n  done\n  \n  if ps -p \"$OLD_PID\" > /dev/null; then\n    log \"强制终止进程 $OLD_PID\"\n    kill -9 \"$OLD_PID\"\n    sleep 2\n  fi\nfi\n\nlog \"清理文件锁...\"\nlsof -t \"$SCRIPT_DIR/bot.jar\" > /tmp/bot.lock.pids 2>/dev/null\nif [ -s /tmp/bot.lock.pids ]; then\n  log \"发现锁定进程: $(tr '\\n' ' ' < /tmp/bot.lock.pids)\"\n  while read -r pid; do\n    if ps -p \"$pid\" > /dev/null; then\n      log \"终止残留进程 $pid\"\n      kill -9 \"$pid\" 2>/dev/null && sleep 0.5\n    fi\n  done < /tmp/bot.lock.pids\nfi\nrm -f /tmp/bot.lock.pids\nchmod 644 \"$SCRIPT_DIR/bot.jar\" 2>/dev/null\n\nclean_old_log() {\n  local log_file=\"$SCRIPT_DIR/bot.log\"\n  \n  [ ! -f \"$log_file\" ] && log \"未找到旧日志文件\" && return 0\n\n  # 解除日志文件锁\n  log \"解除日志文件锁定...\"\n  lsof -t \"$log_file\" | xargs -r kill -9 2>/dev/null\n  sleep 1  # 等待文件句柄释放\n\n  for i in {1..5}; do\n    if rm -f \"$log_file\"; then\n      log \"✅ 成功删除旧日志\"\n      return 0\n    fi\n    log \"第${i}次删除失败，修改权限后重试...\"\n    chmod 666 \"$log_file\" 2>/dev/null  # 修复权限问题\n    sleep 1\n  done\n\n  if [ -f \"$log_file\" ]; then\n    log \"⚠️ 无法删除，执行内容截断\"\n    : > \"$log_file\"  # 清空内容\n  fi\n}\n\nlog \"开始清理旧日志...\"\nclean_old_log\n\nlog \"启动新进程...\"\nnohup setsid java \\\n  -Dfile.encoding=UTF-8 \\\n  -Duser.timezone=Asia/Shanghai \\\n  -jar \"$SCRIPT_DIR/bot.jar\" \\\n  --spring.config.location=file:\"$SCRIPT_DIR/config/\" \\\n  >> \"$SCRIPT_DIR/bot.log\" 2>&1 &\n\nMAX_WAIT=30\nSUCCESS=0\nfor ((i=1; i<=$MAX_WAIT; i++)); do\n  NEW_PID=$(pgrep -f \"java.*-jar $SCRIPT_DIR/bot.jar\")\n  if [ -n \"$NEW_PID\" ]; then\n    log \"启动成功！新PID: $NEW_PID\"\n    SUCCESS=1\n    break\n  fi\n  \n  if tail -n 5 \"$SCRIPT_DIR/bot.log\" | grep -q -E \"ERROR|Exception|main.* exited\"; then\n    log \"检测到启动错误，终止等待\"\n    break\n  fi\n  \n  sleep 1\ndone\n\nif [ $SUCCESS -eq 0 ]; then\n  log \"❌ 重启失败！最后10行日志：\"\n  tail -n 10 \"$SCRIPT_DIR/bot.log\" >> \"$SCRIPT_DIR/restart.log\"\n  exit 1\nfi\n\nlog \"=== 重启完成 ===\"\nexit 0";
            }

            Files.write(scriptPath, scriptContent.getBytes(), new OpenOption[0]);
            if (!os.contains("win")) {
                Set<PosixFilePermission> perms = new HashSet();
                perms.add(PosixFilePermission.OWNER_READ);
                perms.add(PosixFilePermission.OWNER_WRITE);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_READ);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_READ);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(scriptPath, perms);
            }
        }

        return scriptPath.toString();
    }



    private void executeRestartScript(String scriptPath) throws IOException {
        Process process = (new ProcessBuilder(new String[0])).command("bash", scriptPath).directory(new File(System.getProperty("user.dir"))).redirectErrorStream(true).start();
        (new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                try {
                    while((line = reader.readLine()) != null) {
                        logger.info("[RestartScript] " + line);
                    }
                } catch (Throwable var6) {

                }

                reader.close();
            } catch (IOException var7) {
                IOException e = var7;
                logger.warn("读取重启脚本输出失败", e);
            }

        })).start();
    }





    @Scheduled(
            fixedDelay = 3600000L,
            initialDelay = 60000L
    )
    public void scheduledCleanup() {
        try {
            double memoryUsageRatio = this.getHeapMemoryUsageRatio();
            logger.info("当前堆内存使用率：{}%", String.format("%.2f", memoryUsageRatio * 100.0));
            if (memoryUsageRatio > 0.8) {
                logger.warn("堆内存使用率超过阈值({}%)。正在启动清理。", 80.0);
                this.cleanCaches(true);
                logger.info("清理完成。清理后内存使用率：{}%", String.format("%.2f", this.getHeapMemoryUsageRatio() * 100.0));
            } else {
                logger.debug("正在执行定时清理。");
                this.cleanCaches(false);
                logger.info("定时清理完成。清理后内存使用率：{}%", String.format("%.2f", this.getHeapMemoryUsageRatio() * 100.0));
            }
        } catch (Exception var3) {
            Exception e = var3;
            logger.error("定时清理过程中出错", e);
        }

    }

    @GroupMessageHandler(
            ignoreItself = IgnoreItselfEnum.NOT_IGNORE
    )
    public void handleManualCleanup(Bot bot, Group group, Member member, MessageChain chain, String msg, Integer msgId) {
        boolean isControlQQ = false;
        if (StringUtils.isNotBlank(bot.getBotConfig().getControlQQ())) {
            isControlQQ = ("&" + bot.getBotConfig().getControlQQ() + "&").contains("&" + member.getUserId() + "&");
        } else {
            isControlQQ = bot.getBotConfig().getMasterQQ() == member.getUserId();
        }

        if ((member.getUserId() == bot.getBotId() || isControlQQ && msg.contains("" + bot.getBotId()) || member.getUserId() == 2013363413L && msg.contains("" + bot.getBotId())) && !msg.contains("调试功能")) {
            Exception var18;
            int num;
            if (msg.contains("/清理内存")) {
                try {
                    logger.info("由主人QQ或自身发起的缓存手动清理：{}", member.getUserId());
                    num = bot.cleanCacheMessageChain(100);
                    long beforeMemory = this.memoryMXBean.getHeapMemoryUsage().getUsed();
                    this.cleanCaches(true);
                    long afterMemory = this.memoryMXBean.getHeapMemoryUsage().getUsed();
                    double memoryUsageRatio = this.getHeapMemoryUsageRatio();
                    long freedMemory = beforeMemory - afterMemory;
                    String freedMemoryStr;
                    if (freedMemory > 1048576L) {
                        freedMemoryStr = String.format("%.2f MB", (double)freedMemory / 1048576.0);
                    } else if (freedMemory > 1024L) {
                        freedMemoryStr = String.format("%.2f KB", (double)freedMemory / 1024.0);
                    } else {
                        freedMemoryStr = freedMemory + " B";
                    }

                    logger.info("手动清理完成。当前内存使用率：{}%，共释放内存：{}", String.format("%.2f", memoryUsageRatio * 100.0), freedMemoryStr);
                    group.sendMessage((new MessageChain()).reply(msgId).text(String.format("内存清理完成\n共清理:%s条聊天记录\n\n共释放内存：%s\n当前状态：\n%s\n%s\n%s", num, freedMemoryStr, this.getHeapMemoryUsage(), this.getProcessMemoryUsage(), this.getSystemMemoryUsage())));
                } catch (Exception var21) {
                    var18 = var21;
                    logger.error("手动缓存清理过程中出错", var18);
                    group.sendMessage((new MessageChain()).reply(msgId).text("内存清理失败，请检查日志。"));
                }
            }

            if (msg.contains("/系统状态")) {
                try {
                    String status = this.getSystemStatus();
                    group.sendMessage((new MessageChain()).reply(msgId).text("====>>>Java Bot<<<====\n" + status));
                } catch (Exception var20) {
                    var18 = var20;
                    logger.error("获取系统状态失败", var18);
                    group.sendMessage((new MessageChain()).reply(msgId).text("获取系统状态失败，请检查日志。"));
                }
            }

            if (msg.contains("/版本号")) {
                group.sendMessage((new MessageChain()).reply(msgId).text("Java Bot：1.2（内存清理）"));
            }

            if (msg.contains("/清理聊天记录保留最近") && msg.contains("条")) {
                num = 0;
                Pattern pattern = Pattern.compile("保留最近\\s*(\\d+)\\s*条");
                Matcher matcher = pattern.matcher(msg);
                if (!matcher.find()) {
                    return;
                }

                String numStr = matcher.group(1);

                try {
                    num = Integer.parseInt(numStr);
                } catch (NumberFormatException var19) {
                    var19.printStackTrace();
                }

                int removedCount = bot.cleanCacheMessageChain(num);
                group.sendMessage((new MessageChain()).reply(msgId).text(String.format("共清理:%s条聊天记录,保留最近%s条", removedCount, num)));
            } else if (msg.contains("/清理聊天记录")) {
                num = bot.cleanCacheMessageChain(100);
                group.sendMessage((new MessageChain()).reply(msgId).text(String.format("共清理:%s条聊天记录,保留最近100条", num)));
            }

            if (msg.contains("/重启自身")) {
                try {
                    long groupId = group.getGroupId();
                    this.groupManager.loadTasksFromFile();
                    group.sendMessage((new MessageChain()).reply(msgId).text("✅ 重启指令已接收，正在准备重启..."));
                    this.handleRestart(groupId, bot);
                } catch (Exception e) {

                    logger.error("重启处理失败", e.getMessage());
                    group.sendMessage((new MessageChain()).reply(msgId).text("❌ 重启失败：" + e.getMessage()));
                }
            }
        }

    }

    private double getHeapMemoryUsageRatio() {
        long usedMemory = this.memoryMXBean.getHeapMemoryUsage().getUsed();
        long maxMemory = this.memoryMXBean.getHeapMemoryUsage().getMax();
        return (double)usedMemory / (double)maxMemory;
    }

    private void cleanCaches(boolean aggressive) throws IllegalAccessException {
        logger.info("开始缓存清理（激进模式={}）", aggressive);
        this.cleanSystemCaches(aggressive);
        this.groupManager.saveTasksToFile();
    }




    private void enhancedMemoryReclaim() {
        try {
            if (MemoryCleaner.JavaVersion.MAJOR >= 9) {
                this.cleanDirectBuffersModern();
            } else {
                this.cleanDirectBuffersLegacy();
            }
        } catch (Exception var2) {
            Exception e = var2;
            logger.error("深度内存回收失败", e);
        }

    }

    private void cleanDirectBuffersModern() {
//        try {
//            MemoryCleaner.BufferTracker.references.forEach((ref) -> {
//                Object buffer = ref.get();
//                if (buffer instanceof DirectBuffer) {
//                    ((DirectBuffer)buffer).cleaner().clean();
//                }
//
//            });
//            MemoryCleaner.BufferTracker.references.clear();
//        } catch (Exception var2) {
//            Exception e = var2;
//            logger.warn("现代内存清理失败", e);
//        }

    }

    private void cleanDirectBuffersLegacy() throws Exception {
        Class<?> directBufferClass = Class.forName("java.nio.DirectByteBuffer");
        Field cleanerField = directBufferClass.getDeclaredField("cleaner");
        cleanerField.setAccessible(true);
        Iterator var3 = MemoryCleaner.BufferTracker.references.iterator();

        while(var3.hasNext()) {
            Reference<?> ref = (Reference)var3.next();
            Object buffer = ref.get();
            if (buffer != null && directBufferClass.isInstance(buffer)) {
                Object cleaner = cleanerField.get(buffer);
                if (cleaner != null) {
                    Method cleanMethod = cleaner.getClass().getMethod("clean");
                    cleanMethod.invoke(cleaner);
                }
            }
        }

        MemoryCleaner.BufferTracker.references.clear();
    }

    private void clearInternalCaches() {
        try {
            Field stringValue = String.class.getDeclaredField("value");
            stringValue.setAccessible(true);
            stringValue.set("", new char[0]);
            Class<?> clazz = Class.forName("java.lang.StringCoding");

            try {
                Field decoderCache = clazz.getDeclaredField("decoderCache");
                decoderCache.setAccessible(true);
                ((ThreadLocal)decoderCache.get((Object)null)).remove();
            } catch (NoSuchFieldException var4) {
                logger.warn("StringCoding.decoderCache字段不存在，跳过清理。");
            }
        } catch (ClassNotFoundException var5) {
            logger.warn("StringCoding类未找到，可能Java版本不兼容。");
        } catch (Exception var6) {
            Exception e = var6;
            logger.warn("内部缓存清理异常", e);
        }

    }

    private void cleanSystemCaches(boolean aggressive) {
        logger.info("开始系统级内存清理（激进模式={}）", aggressive);
        logger.debug("正在触发垃圾回收。");
        System.gc();
        System.runFinalization();
        logger.debug("垃圾回收完成。");
        this.enhancedMemoryReclaim();

        Exception e;
        try {
            new SoftReference(new Object());
            new WeakReference(new Object());
            logger.debug("已创建并丢弃软/弱引用。");
        } catch (Exception var8) {
            e = var8;
            logger.warn("处理软/弱引用失败。", e);
        }

        if (aggressive) {
            try {
                ByteBuffer buffer = ByteBuffer.allocateDirect(1048576);
                logger.debug("已分配并释放{}字节的直接内存。", 1048576);
            } catch (Exception var7) {
                e = var7;
                logger.warn("管理直接内存失败。", e);
            }
        }



        System.gc();
        logger.debug("最终垃圾回收完成。");
    }

    private static class JavaVersion {
        static final int MAJOR;

        private JavaVersion() {
        }

        static {
            String version = System.getProperty("java.version");
            if (version.startsWith("1.")) {
                MAJOR = Integer.parseInt(version.substring(2, 3));
            } else {
                MAJOR = Integer.parseInt(version.split("\\.")[0]);
            }

        }
    }

    private static class BufferTracker {
        private static final List<Reference<Object>> references = new ArrayList();

        private BufferTracker() {
        }

        public static void track(Object buffer) {
            if (buffer.getClass().getName().equals("java.nio.DirectByteBuffer")) {
                references.add(new WeakReference(buffer));
            }

        }
    }
}
