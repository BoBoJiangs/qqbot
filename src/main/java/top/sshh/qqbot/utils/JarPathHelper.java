package top.sshh.qqbot.utils;

import java.io.File;
import java.net.URL;
import java.security.ProtectionDomain;

public class JarPathHelper {
    public static String getJarDir() {
        try {
            ProtectionDomain protectionDomain = JarPathHelper.class.getProtectionDomain();
            URL jarUrl = protectionDomain.getCodeSource().getLocation();
            File jarFile = new File(jarUrl.toURI());
            return jarFile.getParentFile().getAbsolutePath();
        } catch (Exception e) {
            throw new RuntimeException("无法获取 JAR 所在目录", e);
        }
    }

}