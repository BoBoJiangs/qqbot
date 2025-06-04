//package top.sshh.qqbot.service;
//
//import com.zhuangxv.bot.annotation.GroupMessageHandler;
//import com.zhuangxv.bot.config.BotConfig;
//import com.zhuangxv.bot.core.Bot;
//import com.zhuangxv.bot.core.Group;
//import com.zhuangxv.bot.core.Member;
//import com.zhuangxv.bot.message.MessageChain;
//import com.zhuangxv.bot.utilEnum.IgnoreItselfEnum;
//import org.springframework.stereotype.Component;
//import oshi.SystemInfo;
//import oshi.hardware.CentralProcessor;
//import oshi.hardware.GlobalMemory;
//import oshi.software.os.OSFileStore;
//import top.sshh.qqbot.data.MessageNumber;
//
//import java.net.InetAddress;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.List;
//import java.util.concurrent.TimeUnit;
//
//@Component
//public class SystemStatusMonitor {
//
//
//    @GroupMessageHandler(
//            ignoreItself = IgnoreItselfEnum.ONLY_ITSELF
//    )
//    public void enableScheduled(Bot bot, Group group, Member member, MessageChain messageChain, String message, Integer messageId) throws InterruptedException {
//        BotConfig botConfig = bot.getBotConfig();
//        message = message.trim();
//        if (message.equals("运行状态")) {
//            getSystemInfo(bot,group,messageId);
//        }
//
//    }
//
//    public  void getSystemInfo(Bot bot, Group group,Integer messageId) {
//        SystemInfo si = new SystemInfo();
//        CentralProcessor cpu = si.getHardware().getProcessor();
//        GlobalMemory memory = si.getHardware().getMemory();
//        List<OSFileStore> fileStores = si.getOperatingSystem().getFileSystem().getFileStores();
//
//        // 计算各项指标
//        double cpuUsage = getCpuUsage(cpu);
//        double memoryUsage = getMemoryUsage(memory);
//        double diskUsage = getDiskUsage(fileStores.get(0));
//        String uptime = getSystemUptime(si);
//        String ipAddress = getLocalIpAddress();
//        String currentTime = new SimpleDateFormat("yyyy年MM月dd日HH时mm分ss秒").format(new Date());
//
//        // 使用 StringBuilder 拼接输出
//        StringBuilder status = new StringBuilder();
//        status.append("－－－－－运行状态－－－－－\n");
//        status.append("QQ：").append(bot.getBotId()).append("\n");
//        status.append("昵称：").append(bot.getBotName()).append("\n");
//        status.append(String.format("CPU用率：%.0f%%\n", cpuUsage));
//        status.append(String.format("内存用率：%.0f%%\n", memoryUsage));
//        status.append(String.format("磁盘用率：%.0f%%\n", diskUsage));
//        status.append("运行时长：").append(uptime).append("\n");
//        status.append("IP地址：").append(ipAddress).append("\n");
//        status.append(currentTime);
//
//        group.sendMessage((new MessageChain()).reply(messageId).text(status.toString()));
//    }
//
//    // 获取 CPU 使用率（略，同上）
//    private  double getCpuUsage(CentralProcessor cpu) {
//        long[] prevTicks = cpu.getSystemCpuLoadTicks();
//        try {
//            Thread.sleep(1000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//        return cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100;
//    }
//
//    // 获取内存使用率（略，同上）
//    private  double getMemoryUsage(GlobalMemory memory) {
//        long totalMemory = memory.getTotal();
//        long availableMemory = memory.getAvailable();
//        return (totalMemory - availableMemory) * 100.0 / totalMemory;
//    }
//
//    // 获取磁盘使用率（略，同上）
//    private  double getDiskUsage(OSFileStore fs) {
//        long totalSpace = fs.getTotalSpace();
//        long freeSpace = fs.getFreeSpace();
//        return (totalSpace - freeSpace) * 100.0 / totalSpace;
//    }
//
//    // 获取系统运行时长（略，同上）
//    private  String getSystemUptime(SystemInfo si) {
//        long uptimeSeconds = si.getOperatingSystem().getSystemUptime();
//        long days = TimeUnit.SECONDS.toDays(uptimeSeconds);
//        long hours = TimeUnit.SECONDS.toHours(uptimeSeconds) % 24;
//        long minutes = TimeUnit.SECONDS.toMinutes(uptimeSeconds) % 60;
//        return days + "天" + hours + "小时" + minutes + "分钟";
//    }
//
//    // 获取本机 IP 地址（略，同上）
//    private  String getLocalIpAddress() {
//        try {
//            return InetAddress.getLocalHost().getHostAddress();
//        } catch (Exception e) {
//            return "未知";
//        }
//    }
//}