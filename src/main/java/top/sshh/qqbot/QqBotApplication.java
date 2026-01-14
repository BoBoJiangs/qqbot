package top.sshh.qqbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.net.ServerSocket;

@EnableScheduling
@SpringBootApplication
public class QqBotApplication {

    public static void main(String[] args) {
        String lockEnabledProp = firstNonBlank(System.getProperty("instance.lock.enabled"), System.getenv("INSTANCE_LOCK_ENABLED"));
        boolean lockEnabled = lockEnabledProp == null || lockEnabledProp.isBlank() ? true : Boolean.parseBoolean(lockEnabledProp.trim());
        if (lockEnabled) {
            String lockPortProp = firstNonBlank(System.getProperty("instance.lock.port"), System.getenv("INSTANCE_LOCK_PORT"));
            int lockPort = 62345;
            if (lockPortProp != null && !lockPortProp.isBlank()) {
                try {
                    lockPort = Integer.parseInt(lockPortProp.trim());
                } catch (Exception ignored) {
                }
            }

            ServerSocket serverSocket = null;
            try {
                serverSocket = new ServerSocket(lockPort);
            } catch (IOException e) {
                System.out.println("启动失败，之前启动的程序没有关闭！！！");
                System.exit(1);
            }

            ServerSocket finalServerSocket = serverSocket;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!finalServerSocket.isClosed()) {
                    try {
                        finalServerSocket.close();
                    } catch (IOException e) {
                        System.err.println("Failed to close socket: " + e.getMessage());
                    }
                }
            }));
        }
        SpringApplication.run(QqBotApplication.class, args);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

}
