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
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Value("${napcat.data-root:/home/user/JavaBot}")
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

    public Map<String, Object> createNapcatBot(String account, int hostPort) {
        if (account == null) throw new RuntimeException("QQ号不能为空");
        String qq = account.trim();
        if (!qq.matches("\\d{5,12}")) throw new RuntimeException("QQ号格式不正确");
        if (hostPort < 1 || hostPort > 65535) throw new RuntimeException("端口范围应为 1-65535");

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
                        throw new RuntimeException("端口已被占用: " + hostPort);
                    }
                }
            }
        }

        String uid = System.getenv("NAPCAT_UID");
        String gid = System.getenv("NAPCAT_GID");
        if (uid == null || uid.isBlank()) uid = "0";
        if (gid == null || gid.isBlank()) gid = "0";

        String base = napcatDataRoot == null ? "" : napcatDataRoot.trim();
        if (base.isEmpty()) throw new RuntimeException("napcat.data-root 未配置");
        String hostQqData = base + "/napcat/" + containerName + "/qq_data";
        String hostNapcatConfig = base + "/napcat/" + containerName + "/napcat_config";

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
                        "NAPCAT_GID=" + gid
                )
                .withExposedPorts(containerPort)
                .withHostConfig(hostConfig)
                .exec()
                .getId();

        requireClient().startContainerCmd(id).exec();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("containerId", id);
        result.put("containerName", containerName);
        result.put("hostPort", hostPort);
        result.put("account", qq);
        result.put("dataDir", base + "/napcat/" + containerName);
        return result;
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
