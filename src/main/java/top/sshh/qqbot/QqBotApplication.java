package top.sshh.qqbot;

import com.zhuangxv.bot.EnableBot;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.net.ServerSocket;

@EnableBot
@EnableScheduling
@SpringBootApplication
public class QqBotApplication {

    public static void main(String[] args) {
        ServerSocket serverSocket = null;

        try {
            serverSocket = new ServerSocket(62345);
        } catch (IOException e) {
            System.out.println("启动失败，之前启动的程序没有关闭！！！！");
            System.exit(1);
        }

        // 注册关闭钩子，在程序退出时关闭Socket
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
        SpringApplication.run(QqBotApplication.class, args);
    }

}
