package top.sshh.qqbot.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Duration;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class DockerService {

    private static final Logger logger = LoggerFactory.getLogger(DockerService.class);

    @Value("${docker.host:}")
    private String dockerHost;

    @Value("${napcat.image:docker.1ms.run/mlikiowa/napcat-docker:latest}")
    private String napcatImage;

    @Value("${napcat.data-root:/root}")
    private String napcatDataRoot;

    private volatile DockerClient dockerClient;
    private volatile boolean available = false;
    private volatile String initError = "";
    private volatile String resolvedDockerHost = "";
    private volatile long lastInitAttemptAtMillis = 0L;
    private final String selfContainerIdPrefix;

    @PostConstruct
    public void init() {
        connect(false);
    }

    public DockerService() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null) {
            selfContainerIdPrefix = "";
        } else {
            String trimmed = hostname.trim();
            selfContainerIdPrefix = trimmed.length() > 12 ? trimmed.substring(0, 12) : trimmed;
        }
    }

    private synchronized void connect(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && available && dockerClient != null) return;
        if (!force && now - lastInitAttemptAtMillis < 2000) return;
        lastInitAttemptAtMillis = now;

        String configured = dockerHost == null ? "" : dockerHost.trim();
        String overrideHost = configured.isEmpty() || "auto".equalsIgnoreCase(configured) ? null : configured;
        String[] candidates;
        if (overrideHost == null) {
            candidates = new String[]{null, "unix:///run/docker.sock"};
        } else {
            candidates = new String[]{overrideHost, null};
        }
        tryConnectCandidates(candidates);
    }

    private void tryConnectCandidates(String[] overrideHosts) {
        Exception last = null;
        for (String overrideHost : overrideHosts) {
            try {
                DefaultDockerClientConfig.Builder configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder();
                if (overrideHost != null) {
                    configBuilder.withDockerHost(overrideHost);
                }
                DefaultDockerClientConfig config = configBuilder.build();
                resolvedDockerHost = String.valueOf(config.getDockerHost());

                DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .maxConnections(100)
                        .connectionTimeout(Duration.ofSeconds(5))
                        .responseTimeout(Duration.ofSeconds(5))
                        .build();

                DockerClient client = DockerClientBuilder.getInstance(config)
                        .withDockerHttpClient(httpClient)
                        .build();

                client.pingCmd().exec();

                dockerClient = client;
                available = true;
                initError = "";
                logger.info("Docker service initialized successfully. dockerHost={}", resolvedDockerHost);
                return;
            } catch (Exception e) {
                last = e;
            }
        }

        available = false;
        dockerClient = null;
        initError = last == null ? "Unknown" : last.toString();
        logger.error("Docker service initialization failed. dockerHost={}, error={}", resolvedDockerHost, initError);
    }

    private DockerClient requireClient() {
        DockerClient client = dockerClient;
        if (available && client != null) return client;
        connect(true);
        client = dockerClient;
        if (!available || client == null) {
            throw new RuntimeException("Docker服务未连接 (Docker service not connected). host=" + resolvedDockerHost + ", 原因: " + initError);
        }
        return client;
    }

    public List<Container> listContainers(boolean all) {
        return requireClient().listContainersCmd().withShowAll(all).exec();
    }

    public void restartContainer(String containerId) {
        requireClient().restartContainerCmd(containerId).exec();
    }

    public void restartContainerAsync(String containerId, long delayMillis) {
        CompletableFuture.runAsync(() -> {
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                requireClient().restartContainerCmd(containerId).exec();
            } catch (Throwable e) {
                logger.error("Async restart failed. containerId={}, error={}", containerId, e.toString());
            }
        });
    }

    public void stopContainer(String containerId) {
        if (isSelfContainer(containerId)) {
            throw new RuntimeException("禁止停止当前运行中的服务容器（会导致页面/接口中断）");
        }
        requireClient().stopContainerCmd(containerId).exec();
    }

    public void removeContainer(String containerId) {
        if (isSelfContainer(containerId)) {
            throw new RuntimeException("禁止删除当前运行中的服务容器（会导致页面/接口中断）");
        }
        requireClient().removeContainerCmd(containerId).exec();
    }

    public Map<String, Object> createNapcatBot(String account, int hostPort, int wsPort, String serverIp, String accessToken, String groupId, String controlQQ) {
        if (account == null) throw new RuntimeException("QQ号不能为空");
        String qq = account.trim();
        if (!qq.matches("\\d{5,12}")) throw new RuntimeException("QQ号格式不正确");
        if (hostPort < 1 || hostPort > 65535) throw new RuntimeException("WebUI端口范围应为 1-65535");
        if (wsPort < 1 || wsPort > 65535) throw new RuntimeException("WS端口范围应为 1-65535");
        if (serverIp == null || serverIp.trim().isEmpty()) throw new RuntimeException("服务器IP不能为空");
        if (groupId == null || groupId.trim().isEmpty()) throw new RuntimeException("修炼群号不能为空");
        if (controlQQ == null || controlQQ.trim().isEmpty()) throw new RuntimeException("主号QQ不能为空");

        String containerName = qq + "-" + hostPort;
        List<Container> all = listContainers(true);
        for (Container c : all) {
            String[] names = c.getNames();
            if (names != null) {
                for (String n : names) {
                    if (n != null && n.replace("/", "").equals(containerName)) {
                        throw new RuntimeException("容器已存在: " + containerName);
                    }
                }
            }
            if (c.getPorts() != null) {
                for (ContainerPort p : c.getPorts()) {
                    Integer publicPort = p.getPublicPort();
                    if (publicPort != null && publicPort == hostPort) {
                        throw new RuntimeException("WebUI端口已被占用: " + hostPort);
                    }
                }
            }
        }

        writeConfigFileToHostNapcatConfigDir(qq, wsPort, serverIp.trim(), accessToken.trim());

        String uid = System.getenv("NAPCAT_UID");
        String gid = System.getenv("NAPCAT_GID");
        if (uid == null || uid.isBlank()) uid = "0";
        if (gid == null || gid.isBlank()) gid = "0";

        String base = napcatDataRoot == null ? "" : napcatDataRoot.trim();
        if (base.isEmpty()) throw new RuntimeException("napcat.data-root 未配置");
        String hostQqData = base + "/napcat/qq_data";
        String hostNapcatConfig = base + "/napcat/napcat_config";

        ExposedPort containerPort = ExposedPort.tcp(6099);
        Ports portBindings = new Ports();
        portBindings.bind(containerPort, Ports.Binding.bindPort(hostPort));

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withBinds(
                        new Bind(hostQqData, new Volume("/app/.config/QQ")),
                        new Bind(hostNapcatConfig, new Volume("/app/napcat/config"))
                )
                .withRestartPolicy(RestartPolicy.alwaysRestart());

        String id = requireClient().createContainerCmd(napcatImage)
                .withName(containerName)
                .withEnv(
                        "ACCOUNT=" + qq,
                        "NAPCAT_UID=" + uid,
                        "NAPCAT_GID=" + gid,
                        "WS_ENABLE=true",
                        "WS_URL=ws://host.docker.internal:" + wsPort
                )
                .withExposedPorts(containerPort)
                .withHostConfig(hostConfig)
                .exec()
                .getId();

        requireClient().startContainerCmd(id).exec();

        try {
            appendBotConfigToYaml(wsPort, accessToken, groupId.trim(), controlQQ.trim(), controlQQ.trim());
        } catch (Exception e) {
            logger.error("Failed to append config to application-local.yml", e);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("containerId", id);
        result.put("containerName", containerName);
        result.put("hostPort", hostPort);
        result.put("account", qq);
        result.put("dataDir", base + "/napcat/" + containerName);
        return result;
    }

    private void writeConfigFileToHostNapcatConfigDir(String qq, int wsPort, String serverIp, String token) {
        String fileName = "onebot11_" + qq + ".json";
        String jsonContent = "{\n" +
                "  \"network\": {\n" +
                "    \"httpServers\": [],\n" +
                "    \"httpSseServers\": [],\n" +
                "    \"httpClients\": [],\n" +
                "    \"websocketServers\": [],\n" +
                "    \"websocketClients\": [\n" +
                "      {\n" +
                "        \"enable\": false,\n" +
                "        \"name\": \"" + wsPort + "\",\n" +
                "        \"url\": \"ws://" + serverIp + ":" + wsPort + "\",\n" +
                "        \"reportSelfMessage\": true,\n" +
                "        \"messagePostFormat\": \"array\",\n" +
                "        \"token\": \"" + token + "\",\n" +
                "        \"debug\": true,\n" +
                "        \"heartInterval\": 30000,\n" +
                "        \"reconnectInterval\": 30000\n" +
                "      }\n" +
                "    ],\n" +
                "    \"plugins\": []\n" +
                "  },\n" +
                "  \"musicSignUrl\": \"\",\n" +
                "  \"enableLocalFile2Url\": false,\n" +
                "  \"parseMultMsg\": false\n" +
                "}";

        String writerContainerId = null;
        try {
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withBinds(new Bind("/root/napcat_config", new Volume("/mnt")));

            writerContainerId = requireClient()
                    .createContainerCmd(napcatImage)
                    .withHostConfig(hostConfig)
                    .exec()
                    .getId();

            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 TarArchiveOutputStream tarOut = new TarArchiveOutputStream(bos)) {

                byte[] contentBytes = jsonContent.getBytes(StandardCharsets.UTF_8);

                TarArchiveEntry entry = new TarArchiveEntry(fileName);
                entry.setSize(contentBytes.length);
                entry.setMode(0644);

                tarOut.putArchiveEntry(entry);
                tarOut.write(contentBytes);
                tarOut.closeArchiveEntry();
                tarOut.finish();

                try (InputStream tarInputStream = new ByteArrayInputStream(bos.toByteArray())) {
                    requireClient().copyArchiveToContainerCmd(writerContainerId)
                            .withTarInputStream(tarInputStream)
                            .withRemotePath("/mnt")
                            .exec();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("写入宿主机 /root/napcat_config 失败: " + e.getMessage(), e);
        } finally {
            if (writerContainerId != null) {
                try {
                    requireClient().removeContainerCmd(writerContainerId).withForce(true).exec();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void appendBotConfigToYaml(int wsPort, String accessToken, String groupId, String controlQQ, String masterQQ) throws java.io.IOException {
        Path configPath = Path.of("config/application-local.yml");
        if (!Files.exists(configPath)) {
            Files.createDirectories(configPath.getParent());
            Path srcPath = Path.of("src/main/resources/application-local.yml");
            if (Files.exists(srcPath)) {
                Files.copy(srcPath, configPath);
            } else {
                Files.writeString(configPath, "bot:\n");
            }
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(configPath);
        } catch (java.io.IOException e) {
            lines = new java.util.ArrayList<>();
        }

        boolean botKeyFound = false;
        int botLineIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("bot:") && !line.trim().startsWith("#")) {
                botKeyFound = true;
                botLineIndex = i;
                break;
            }
        }

        if (!botKeyFound) {
            lines.add("bot:");
            botLineIndex = lines.size() - 1;
        }

        String newConfig = String.format(
                "  - type: ws-reverse\n" +
                "    url: ws://0.0.0.0:%d\n" +
                "    accessToken: %s\n" +
                "    #    修炼群号\n" +
                "    groupId: %s\n" +
                "    #    主号qq\n" +
                "    controlQQ: %s\n" +
                "    #    主号qq\n" +
                "    masterQQ: %s",
                wsPort, accessToken, groupId, controlQQ, masterQQ
        );

        String[] configLines = newConfig.split("\n");
        int insertIndex = botLineIndex + 1;
        for (String line : configLines) {
            if (insertIndex <= lines.size()) {
                lines.add(insertIndex++, line);
            } else {
                lines.add(line);
                insertIndex++;
            }
        }

        Files.write(configPath, lines);
    }

    private boolean isSelfContainer(String containerId) {
        if (containerId == null || containerId.isEmpty()) return false;
        if (selfContainerIdPrefix == null || selfContainerIdPrefix.isEmpty()) return false;
        return containerId.startsWith(selfContainerIdPrefix);
    }

    public boolean isSelfContainerId(String containerId) {
        return isSelfContainer(containerId);
    }

    public Map<String, Object> diagnose(boolean reconnect) {
        if (reconnect) connect(true);
        Map<String, Object> info = new HashMap<>();
        info.put("available", available && dockerClient != null);
        info.put("dockerHostConfig", dockerHost);
        info.put("dockerHostResolved", resolvedDockerHost);
        info.put("initError", initError);
        info.put("selfContainerIdPrefix", selfContainerIdPrefix);
        info.put("napcatImage", napcatImage);
        info.put("napcatDataRoot", napcatDataRoot);
        if (resolvedDockerHost != null && resolvedDockerHost.startsWith("unix://")) {
            String socketPath = resolvedDockerHost.substring("unix://".length());
            info.put("unixSocketPath", socketPath);
            try {
                Path p = Path.of(socketPath);
                info.put("unixSocketExists", Files.exists(p));
                info.put("unixSocketReadable", Files.isReadable(p));
                info.put("unixSocketWritable", Files.isWritable(p));
            } catch (Exception e) {
                info.put("unixSocketCheckError", e.toString());
            }
        }
        if (available && dockerClient != null) {
            try {
                dockerClient.pingCmd().exec();
                info.put("ping", "OK");
            } catch (Exception e) {
                info.put("ping", e.toString());
            }
        }
        return info;
    }
}
