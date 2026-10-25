package io.nanpanel.core;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.utils.Serialization;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Converts supported creation inputs into namespace-scoped Kubernetes resources. */
@ApplicationScoped
public class KubernetesApplicationManifestFactory {
  private static final Pattern IMAGE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,511}");
  private static final Pattern LABEL =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
  private static final Pattern COMPOSE_INTERPOLATION = Pattern.compile("\\$(?:\\$|\\{|[A-Za-z_])");
  private static final int MAX_SOURCE_LENGTH = 262_144;
  private static final int MAX_SERVICES = 32;

  public record CreateRequest(String applicationName, String displayName, String source,
      String image, Integer replicas, Integer containerPort, Integer servicePort,
      Map<String, String> environment, String definition) {}

  public record Definition(String templateId, List<HasMetadata> resources, List<String> notes) {}

  public Definition create(CreateRequest input, String bookinfoManifest) {
    if (input == null || input.source() == null) throw invalid("请选择应用创建方式");
    return switch (input.source()) {
      case "BOOKINFO" -> new Definition("bookinfo", parseManifest(bookinfoManifest), List.of(
          "Bookinfo 会创建 4 个 ServiceAccount、4 个 Service 和 6 个 Deployment。"));
      case "FORM" -> graphical(input);
      case "DOCKER_RUN" -> dockerRun(input);
      case "DOCKER_COMPOSE" -> dockerCompose(input);
      case "KUBERNETES" -> kubernetes(input);
      default -> throw invalid("不支持的应用创建方式");
    };
  }

  private Definition graphical(CreateRequest input) {
    String name = required(input.applicationName(), "应用标识");
    String image = image(input.image());
    int replicas = replicas(input.replicas());
    List<PortMapping> ports = graphicalPorts(input.containerPort(), input.servicePort());
    Map<String, String> environment = environment(input.environment());
    ContainerDefinition container = new ContainerDefinition(name, name, image, replicas,
        null, null, environment, ports);
    return new Definition("graphical", containerResources(name, List.of(container)),
        List.of("图形化创建生成一个 Deployment" + (ports.isEmpty() ? "。" : " 和一个 ClusterIP Service。"),
            "环境变量会写入 Deployment YAML；请勿在此处填写密码、令牌或其他 Secret。"));
  }

  private Definition dockerRun(CreateRequest input) {
    List<String> tokens = tokenize(required(input.definition(), "docker run 命令"));
    if (!tokens.isEmpty() && tokens.getFirst().equals("$")) tokens.removeFirst();
    if (!tokens.isEmpty() && tokens.getFirst().equals("sudo")) tokens.removeFirst();
    int cursor = 0;
    if (!tokens.isEmpty() && (tokens.getFirst().equals("docker") || tokens.getFirst().equals("podman"))) {
      if (tokens.size() < 2 || !tokens.get(1).equals("run")) {
        throw invalid("请粘贴 docker run 或 podman run 命令");
      }
      cursor = 2;
    }

    List<PortMapping> ports = new ArrayList<>();
    Map<String, String> environment = new LinkedHashMap<>();
    String entrypoint = null;
    String image = null;
    while (cursor < tokens.size()) {
      String token = tokens.get(cursor++);
      if (token.equals("--")) {
        if (cursor >= tokens.size()) throw invalid("docker run 命令缺少镜像");
        image = tokens.get(cursor++);
        break;
      }
      if (!token.startsWith("-")) {
        image = token;
        break;
      }
      if (token.equals("-d") || token.equals("--detach") || token.equals("--rm")
          || token.equals("-i") || token.equals("-t") || token.equals("-it")
          || token.equals("-ti") || token.equals("--interactive") || token.equals("--tty")) {
        continue;
      }
      if (token.equals("-P") || token.equals("--publish-all")) {
        throw invalid("不支持 Docker 随机发布端口；请使用 -p 明确声明端口映射");
      }
      if (token.equals("-p") || token.equals("--publish") || token.startsWith("--publish=")) {
        String value = optionValue(tokens, cursor, token, "--publish");
        if (!token.contains("=")) cursor++;
        ports.add(parsePort(value, true));
        continue;
      }
      if (token.equals("--expose") || token.startsWith("--expose=")) {
        String value = optionValue(tokens, cursor, token, "--expose");
        if (!token.contains("=")) cursor++;
        ports.add(parsePort(value, false));
        continue;
      }
      if (token.equals("-e") || token.equals("--env") || token.startsWith("--env=")) {
        String value = optionValue(tokens, cursor, token, "--env");
        if (!token.contains("=")) cursor++;
        putEnvironment(environment, value, false);
        continue;
      }
      if (token.startsWith("-e") && token.length() > 2) {
        putEnvironment(environment, token.substring(2), false);
        continue;
      }
      if (token.equals("--entrypoint") || token.startsWith("--entrypoint=")) {
        entrypoint = optionValue(tokens, cursor, token, "--entrypoint");
        if (!token.contains("=")) cursor++;
        continue;
      }
      if (token.equals("--name") || token.equals("--restart") || token.equals("--pull")
          || token.equals("--platform")) {
        if (token.equals("--platform")) throw invalid("不支持 --platform；请使用与目标集群节点架构兼容的镜像");
        optionValue(tokens, cursor, token, token);
        cursor++;
        continue;
      }
      if (token.startsWith("--name=") || token.startsWith("--restart=")
          || token.startsWith("--pull=")) continue;
      if (token.startsWith("--platform=")) throw invalid("不支持 --platform；请使用与目标集群节点架构兼容的镜像");
      if (token.equals("--env-file")) throw invalid("--env-file 不会导入；请在向导中填写非敏感环境变量");
      if (token.equals("-v") || token.equals("--volume") || token.equals("--mount")) {
        throw invalid("Docker 主机目录和 Volume 不会导入；请先在集群创建 PVC，再编辑 Deployment YAML");
      }
      if (token.equals("--privileged") || token.equals("--network") || token.startsWith("--network=")
          || token.equals("--net") || token.startsWith("--net=")) {
        throw invalid("不支持 privileged 或 Docker 网络模式；应用会使用 Kubernetes Namespace 网络");
      }
      if (token.length() > 2 && token.startsWith("-p")) {
        ports.add(parsePort(token.substring(2), true));
        continue;
      }
      throw invalid("暂不支持 docker run 参数: " + token);
    }
    if (image == null) throw invalid("docker run 命令缺少镜像");
    image = image(image);
    String name = required(input.applicationName(), "应用标识");
    List<String> args = cursor < tokens.size() ? List.copyOf(tokens.subList(cursor, tokens.size())) : null;
    ContainerDefinition container = new ContainerDefinition(name, name, image, 1,
        entrypoint == null ? null : List.of(entrypoint), args, environment, distinctPorts(ports));
    List<String> notes = new ArrayList<>();
    notes.add("Docker -p 的容器端口和发布端口会转换为 ClusterIP Service；不会创建 NodePort 或 Ingress。");
    notes.add("Docker 容器重启策略由 Kubernetes Deployment 接管；--name 使用应用标识，--restart、--pull 和交互终端选项不导入。");
    notes.add("环境变量会写入 Deployment YAML；请勿粘贴密码、令牌或 --env-file 内容。");
    if (ports.isEmpty()) notes.add("命令未声明端口，因此只会创建 Deployment，不会生成 Service。");
    return new Definition("docker-run", containerResources(name, List.of(container)), List.copyOf(notes));
  }

  private Definition dockerCompose(CreateRequest input) {
    String yaml = required(input.definition(), "Docker Compose YAML");
    Object parsed = parseYamlValue(yaml);
    if (!(parsed instanceof Map<?, ?> document)) throw invalid("Compose 文件顶层必须是 services 对象");
    if (containsComposeInterpolation(parsed)) {
      throw invalid("Compose 环境变量插值必须在导入前解析（例如 ${IMAGE} 或 $IMAGE）");
    }
    Object servicesValue = document.get("services");
    if (!(servicesValue instanceof Map<?, ?> services) || services.isEmpty()) {
      throw invalid("Compose 文件至少需要一个 services 条目");
    }
    Set<String> composeFields = Set.of("services", "version", "name", "volumes", "secrets",
        "configs", "networks");
    for (Object key : document.keySet()) {
      if (!composeFields.contains(String.valueOf(key))) {
        throw invalid("Compose 顶层字段暂不支持: " + key);
      }
    }
    if (services.size() > MAX_SERVICES) throw invalid("Compose 单次最多导入 32 个服务");
    if (nonEmpty(document.get("volumes")) || nonEmpty(document.get("secrets"))
        || nonEmpty(document.get("configs")) || nonEmpty(document.get("networks"))) {
      throw invalid("Compose volumes、secrets、configs 和自定义 networks 不会自动创建；请先转换成集群内资源");
    }

    String appName = required(input.applicationName(), "应用标识");
    List<ContainerDefinition> containers = new ArrayList<>();
    Set<String> resourceNames = new LinkedHashSet<>();
    for (Map.Entry<?, ?> serviceEntry : services.entrySet()) {
      String serviceKey = required(string(serviceEntry.getKey()), "Compose 服务名称");
      if (!(serviceEntry.getValue() instanceof Map<?, ?> service)) {
        throw invalid("Compose 服务必须是对象: " + serviceKey);
      }
      validateComposeService(service, serviceKey);
      String resourceName = prefixedResourceName(appName, serviceKey);
      if (!resourceNames.add(resourceName)) throw invalid("Compose 服务名转换后重复: " + resourceName);
      String image = image(string(service.get("image")));
      int replicas = composeReplicas(service.get("deploy"));
      Map<String, String> environment = composeEnvironment(service.get("environment"));
      List<PortMapping> ports = composePorts(service.get("ports"), true);
      ports.addAll(composePorts(service.get("expose"), false));
      List<String> command = composeCommand(service.get("entrypoint"), "entrypoint");
      List<String> args = composeCommand(service.get("command"), "command");
      String workingDirectory = string(service.get("working_dir"));
      String containerName = service.containsKey("container_name")
          ? safeContainerName(required(string(service.get("container_name")), "container_name"))
          : safeContainerName(serviceKey);
      ContainerDefinition container = new ContainerDefinition(resourceName,
          containerName, image, replicas, command, args, environment,
          distinctPorts(ports), workingDirectory);
      containers.add(container);
    }
    List<String> notes = new ArrayList<>();
    notes.add("Compose 服务会转换为 Deployment；声明端口的服务另生成 ClusterIP Service。");
    notes.add("Compose host port 不会绑定到节点，Kubernetes 服务也不会自动创建 NodePort 或 Ingress。");
    notes.add("环境变量会写入 Deployment YAML；请勿导入密码、令牌、env_file、secrets 或 configs。");
    if (nonBlank(string(document.get("name")))) {
      notes.add("Compose 项目名由应用标识替代，生成的 Kubernetes 资源会使用应用标识作为前缀。");
    }
    if (services.values().stream().anyMatch(value -> value instanceof Map<?, ?> service
        && service.containsKey("depends_on"))) {
      notes.add("depends_on 的启动顺序不会在 Kubernetes 中执行。");
    }
    return new Definition("docker-compose", containerResources(appName, containers), List.copyOf(notes));
  }

  private Definition kubernetes(CreateRequest input) {
    String yaml = required(input.definition(), "Kubernetes YAML");
    return new Definition("kubernetes-manifest", parseManifest(yaml), List.of(
        "只导入 Deployment、Service 和 ServiceAccount；集群会补充分配 namespace 和应用所有权标签。",
        "Secret、ConfigMap、Ingress、Namespace、RBAC、自定义资源及集群级资源不会导入。"));
  }

  private static void validateComposeService(Map<?, ?> service, String serviceName) {
    if (nonEmpty(service.get("volumes"))) {
      throw invalid("Compose 服务 " + serviceName + " 使用了 volumes；请先在集群创建 PVC，并改用 Kubernetes YAML");
    }
    if (nonEmpty(service.get("env_file"))) throw invalid("Compose env_file 不会导入，请填写非敏感 environment 值");
    if (Boolean.TRUE.equals(service.get("privileged")) || nonEmpty(service.get("devices"))
        || nonEmpty(service.get("cap_add")) || service.containsKey("network_mode")) {
      throw invalid("Compose privileged、devices、cap_add 和 network_mode 不受支持");
    }
    Set<String> allowed = Set.of("image", "container_name", "ports", "expose", "environment",
        "command", "entrypoint", "deploy", "depends_on", "working_dir");
    for (Object key : service.keySet()) {
      if (!allowed.contains(String.valueOf(key))) {
        throw invalid("Compose 字段暂不支持: services." + serviceName + "." + key);
      }
    }
    if (!nonBlank(string(service.get("image")))) {
      throw invalid("Compose 服务 " + serviceName + " 必须使用已构建 image；build 不会在集群导入时执行");
    }
    if (service.get("deploy") instanceof Map<?, ?> deploy) {
      for (Object key : deploy.keySet()) {
        if (!"replicas".equals(String.valueOf(key))) {
          throw invalid("Compose deploy 目前只支持 replicas，不支持 " + key);
        }
      }
    }
  }

  private static List<HasMetadata> containerResources(String appName,
      List<ContainerDefinition> containers) {
    List<Map<String, Object>> documents = new ArrayList<>();
    documents.add(resource("v1", "ServiceAccount", appName,
        Map.of("metadata", Map.of("name", appName))));
    for (ContainerDefinition container : containers) {
      String appLabel = container.resourceName();
      Map<String, Object> labels = Map.of("app", appLabel);
      Map<String, Object> podSpec = new LinkedHashMap<>();
      podSpec.put("serviceAccountName", appName);
      podSpec.put("containers", List.of(container.toMap()));
      Map<String, Object> deployment = new LinkedHashMap<>();
      deployment.put("apiVersion", "apps/v1");
      deployment.put("kind", "Deployment");
      deployment.put("metadata", Map.of("name", container.resourceName(), "labels", labels));
      deployment.put("spec", Map.of("replicas", container.replicas(),
          "selector", Map.of("matchLabels", labels),
          "template", Map.of("metadata", Map.of("labels", labels), "spec", podSpec)));
      documents.add(deployment);
      if (!container.ports().isEmpty()) {
        List<Map<String, Object>> servicePorts = container.ports().stream().map(port -> Map.<String, Object>of(
            "name", port.name(), "port", port.servicePort(), "targetPort", port.containerPort(),
            "protocol", port.protocol())).toList();
        documents.add(resource("v1", "Service", container.resourceName(), Map.of("spec",
            Map.of("type", "ClusterIP", "selector", labels, "ports", servicePorts))));
      }
    }
    if (documents.size() > 64) throw invalid("每个应用最多导入 64 个 Kubernetes 资源");
    return parseManifest(toYamlDocuments(documents));
  }

  private static Map<String, Object> resource(String apiVersion, String kind, String name,
      Map<String, Object> fields) {
    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("apiVersion", apiVersion);
    resource.put("kind", kind);
    resource.put("metadata", Map.of("name", name));
    resource.putAll(fields);
    return resource;
  }

  private static String toYamlDocuments(List<Map<String, Object>> documents) {
    return String.join("\n---\n", documents.stream().map(Serialization::asYaml)
        .map(String::strip).toList());
  }

  private static List<HasMetadata> parseManifest(String yaml) {
    if (yaml == null || yaml.isBlank()) throw invalid("YAML 清单不能为空");
    if (yaml.length() > MAX_SOURCE_LENGTH) throw invalid("YAML 清单超过 256 KiB 限制");
    Object parsed;
    try {
      parsed = Serialization.unmarshal(yaml);
    } catch (RuntimeException failure) {
      throw invalid("无法解析 YAML 清单");
    }
    List<HasMetadata> resources = new ArrayList<>();
    collectResources(parsed, resources);
    if (resources.isEmpty()) throw invalid("YAML 中没有可导入的 Kubernetes 资源");
    return resources;
  }

  private static void collectResources(Object parsed, List<HasMetadata> resources) {
    if (parsed instanceof KubernetesList list) {
      if (list.getItems() != null) list.getItems().forEach(item -> collectResources(item, resources));
    } else if (parsed instanceof HasMetadata resource) {
      resources.add(resource);
    } else if (parsed instanceof Collection<?> collection) {
      collection.forEach(item -> collectResources(item, resources));
    } else {
      throw invalid("YAML 文档必须是 Kubernetes Deployment、Service 或 ServiceAccount");
    }
  }

  private static Object parseYamlValue(String yaml) {
    if (yaml == null || yaml.isBlank()) throw invalid("Docker Compose YAML 不能为空");
    if (yaml.length() > MAX_SOURCE_LENGTH) throw invalid("Docker Compose YAML 超过 256 KiB 限制");
    try {
      return Serialization.unmarshal(yaml);
    } catch (RuntimeException failure) {
      throw invalid("无法解析 Docker Compose YAML");
    }
  }

  private static boolean containsComposeInterpolation(Object value) {
    if (value instanceof String text) return COMPOSE_INTERPOLATION.matcher(text).find();
    if (value instanceof Map<?, ?> map) return map.values().stream()
        .anyMatch(KubernetesApplicationManifestFactory::containsComposeInterpolation);
    if (value instanceof Collection<?> collection) return collection.stream()
        .anyMatch(KubernetesApplicationManifestFactory::containsComposeInterpolation);
    return false;
  }

  private static List<PortMapping> graphicalPorts(Integer containerPort, Integer servicePort) {
    if (containerPort == null) {
      if (servicePort != null) throw invalid("填写 Service 端口时也必须填写容器端口");
      return List.of();
    }
    validatePort(containerPort);
    int publishedPort = servicePort == null ? containerPort : servicePort;
    validatePort(publishedPort);
    return List.of(new PortMapping(publishedPort, containerPort, "TCP"));
  }

  private static List<PortMapping> composePorts(Object value, boolean published) {
    List<PortMapping> ports = new ArrayList<>();
    if (value == null) return ports;
    if (!(value instanceof Collection<?> values)) throw invalid("Compose ports 和 expose 必须是数组");
    for (Object item : values) {
      if (item instanceof Map<?, ?> mapping) {
        if ("host".equals(string(mapping.get("mode")))) {
          throw invalid("Compose host mode 端口不会导入，请使用普通 ports 映射");
        }
        Set<String> fields = Set.of("target", "published", "protocol", "host_ip");
        for (Object key : mapping.keySet()) {
          if (!fields.contains(String.valueOf(key))) {
            throw invalid("Compose 端口字段暂不支持: " + key);
          }
        }
        if (!published && mapping.get("published") != null) {
          throw invalid("Compose expose 不支持 published 端口");
        }
        if (nonBlank(string(mapping.get("host_ip")))) {
          throw invalid("Compose host_ip 绑定不会导入；Service 使用集群内 ClusterIP 地址");
        }
        int target = portNumber(mapping.get("target"), "container port");
        Object publishedValue = mapping.get("published");
        int servicePort = published && publishedValue != null
            ? portNumber(publishedValue, "published port") : target;
        ports.add(new PortMapping(servicePort, target, protocol(string(mapping.get("protocol")))));
      } else if (item instanceof String text) {
        if (!published && text.substring(0, text.indexOf('/') < 0 ? text.length()
            : text.indexOf('/')).contains(":")) {
          throw invalid("Compose expose 只接受容器端口，不接受端口映射");
        }
        ports.add(parsePort(text, published));
      } else {
        throw invalid("Compose port 必须是字符串或长格式对象");
      }
    }
    return ports;
  }

  private static PortMapping parsePort(String value, boolean published) {
    String text = required(value, "端口映射");
    String protocol = "TCP";
    int slash = text.lastIndexOf('/');
    if (slash >= 0) {
      protocol = protocol(text.substring(slash + 1));
      text = text.substring(0, slash);
    }
    if (text.contains("-") || text.startsWith("[") || text.chars().filter(ch -> ch == ':').count() > 2) {
      throw invalid("端口范围和 IPv6 主机绑定暂不支持: " + value);
    }
    String[] parts = text.split(":", -1);
    if (!published && parts.length != 1) {
      throw invalid("容器 expose 只接受容器端口，不接受 hostPort:containerPort 映射");
    }
    int target;
    int servicePort;
    if (parts.length == 1) {
      target = portNumber(parts[0], "container port");
      servicePort = target;
    } else if (parts.length == 2) {
      servicePort = published ? portNumber(parts[0], "published port") : portNumber(parts[1], "container port");
      target = portNumber(parts[1], "container port");
    } else if (parts.length == 3) {
      throw invalid("不支持 Docker 主机地址绑定；请直接使用 hostPort:containerPort");
    } else {
      throw invalid("无法解析端口映射: " + value);
    }
    return new PortMapping(servicePort, target, protocol);
  }

  private static Map<String, String> composeEnvironment(Object value) {
    Map<String, String> result = new LinkedHashMap<>();
    if (value == null) return result;
    if (value instanceof Map<?, ?> values) {
      values.forEach((key, item) -> {
        if (item == null) throw invalid("Compose environment 不支持从宿主机继承空值: " + key);
        putEnvironment(result, string(key) + "=" + item, true);
      });
    } else if (value instanceof Collection<?> values) {
      values.forEach(item -> putEnvironment(result, string(item), true));
    } else {
      throw invalid("Compose environment 必须是对象或数组");
    }
    return result;
  }

  private static Map<String, String> environment(Map<String, String> values) {
    Map<String, String> result = new LinkedHashMap<>();
    if (values == null) return result;
    if (values.size() > 64) throw invalid("每个应用最多导入 64 个环境变量");
    values.forEach((key, value) -> {
      if (value == null) throw invalid("环境变量值不能为空");
      putEnvironment(result, key + "=" + value, false);
    });
    return result;
  }

  private static void putEnvironment(Map<String, String> environment, String definition,
      boolean rejectComposeInterpolation) {
    int equals = definition.indexOf('=');
    if (equals <= 0) throw invalid("环境变量必须写成 KEY=VALUE；不会读取宿主机环境变量");
    String key = definition.substring(0, equals).trim();
    String value = definition.substring(equals + 1);
    if (!LABEL.matcher(key).matches()) throw invalid("环境变量名称无效: " + key);
    if (value.length() > 4096) throw invalid("环境变量值超过 4096 字符限制: " + key);
    if (rejectComposeInterpolation && (value.contains("${") || value.contains("$$"))) {
      throw invalid("Compose environment 插值需要先在导入前解析: " + key);
    }
    if (!environment.containsKey(key) && environment.size() >= 64) {
      throw invalid("每个容器最多导入 64 个环境变量");
    }
    if (environment.putIfAbsent(key, value) != null) throw invalid("环境变量重复: " + key);
  }

  private static List<String> composeCommand(Object value, String field) {
    if (value == null) return null;
    if (value instanceof Collection<?> values) {
      List<String> command = values.stream().map(KubernetesApplicationManifestFactory::string).toList();
      if (command.stream().anyMatch(String::isBlank)) throw invalid("Compose " + field + " 包含空参数");
      return command;
    }
    if (value instanceof String text) return tokenize(text);
    throw invalid("Compose " + field + " 必须是字符串或字符串数组");
  }

  private static List<String> tokenize(String command) {
    if (command.length() > MAX_SOURCE_LENGTH) throw invalid("命令超过 256 KiB 限制");
    List<String> tokens = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    char quote = 0;
    boolean escaped = false;
    boolean started = false;
    for (int index = 0; index < command.length(); index++) {
      char current = command.charAt(index);
      if (escaped) {
        token.append(current);
        escaped = false;
        started = true;
      } else if (current == '\\' && quote != '\'') {
        if (index + 1 < command.length() && command.charAt(index + 1) == '\n') {
          index++;
        } else if (index + 2 < command.length() && command.charAt(index + 1) == '\r'
            && command.charAt(index + 2) == '\n') {
          index += 2;
        } else {
          escaped = true;
          started = true;
        }
      } else if (quote != 0) {
        if (current == quote) quote = 0;
        else token.append(current);
        started = true;
      } else if (current == '\'' || current == '"') {
        quote = current;
        started = true;
      } else if (Character.isWhitespace(current)) {
        if (started) {
          tokens.add(token.toString());
          token.setLength(0);
          started = false;
        }
      } else {
        token.append(current);
        started = true;
      }
    }
    if (escaped || quote != 0) throw invalid("命令中的引号或转义符没有闭合");
    if (started) tokens.add(token.toString());
    return tokens;
  }

  private static int composeReplicas(Object deployValue) {
    if (deployValue == null) return 1;
    if (!(deployValue instanceof Map<?, ?> deploy)) throw invalid("Compose deploy 必须是对象");
    if (!deploy.containsKey("replicas")) return 1;
    try {
      return replicas(Integer.parseInt(String.valueOf(deploy.get("replicas"))));
    } catch (NumberFormatException failure) {
      throw invalid("Compose deploy.replicas 必须是 0–20 的整数");
    }
  }

  private static int replicas(Integer value) {
    int replicas = value == null ? 1 : value;
    if (replicas < 0 || replicas > 20) throw invalid("Deployment 副本数必须在 0–20 之间");
    return replicas;
  }

  private static int portNumber(Object value, String label) {
    if (value == null) throw invalid(label + " 缺失");
    try {
      int port = Integer.parseInt(String.valueOf(value));
      validatePort(port);
      return port;
    } catch (NumberFormatException failure) {
      throw invalid(label + " 必须是 1–65535 的整数");
    }
  }

  private static void validatePort(int port) {
    if (port < 1 || port > 65_535) throw invalid("端口必须在 1–65535 之间");
  }

  private static String protocol(String value) {
    String normalized = value == null || value.isBlank() ? "TCP" : value.toUpperCase();
    if (!Set.of("TCP", "UDP", "SCTP").contains(normalized)) throw invalid("不支持的传输协议: " + value);
    return normalized;
  }

  private static List<PortMapping> distinctPorts(List<PortMapping> ports) {
    Map<String, PortMapping> unique = new LinkedHashMap<>();
    for (PortMapping port : ports) {
      String key = port.servicePort() + "/" + port.protocol();
      PortMapping previous = unique.putIfAbsent(key, port);
      if (previous != null && previous.containerPort() != port.containerPort()) {
        throw invalid("同一个 Service 端口映射到了多个容器端口: " + key);
      }
    }
    return List.copyOf(unique.values());
  }

  private static String optionValue(List<String> tokens, int cursor, String token, String option) {
    int equals = token.indexOf('=');
    if (equals >= 0) return token.substring(equals + 1);
    if (cursor >= tokens.size()) throw invalid(option + " 缺少参数");
    return tokens.get(cursor);
  }

  private static String prefixedResourceName(String appName, String serviceName) {
    String suffix = safeContainerName(serviceName);
    String result = appName + "-" + suffix;
    if (result.length() > 63) result = result.substring(0, 63).replaceAll("-+$", "");
    return result;
  }

  private static String safeContainerName(String name) {
    String normalized = name.toLowerCase().replaceAll("[^a-z0-9-]+", "-")
        .replaceAll("^-+|-+$", "");
    if (normalized.isEmpty()) throw invalid("服务名称不能转换为 Kubernetes 名称");
    if (!Character.isLetterOrDigit(normalized.charAt(0))) normalized = "app-" + normalized;
    return normalized.length() > 63 ? normalized.substring(0, 63).replaceAll("-+$", "") : normalized;
  }

  private static String image(String value) {
    String image = required(value, "镜像").trim();
    if (!IMAGE.matcher(image).matches()) throw invalid("容器镜像格式无效");
    return image;
  }

  private static String required(String value, String label) {
    if (!nonBlank(value)) throw invalid(label + "为必填项");
    return value.trim();
  }

  private static boolean nonBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean nonEmpty(Object value) {
    return value instanceof Collection<?> collection && !collection.isEmpty()
        || value instanceof Map<?, ?> map && !map.isEmpty();
  }

  private static String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static ApplicationDefinitionException invalid(String message) {
    return new ApplicationDefinitionException(message);
  }

  private record PortMapping(int servicePort, int containerPort, String protocol) {
    private String name() {
      return "p-" + servicePort + "-" + containerPort + "-" + protocol.toLowerCase();
    }
  }

  private record ContainerDefinition(String resourceName, String containerName, String image,
      int replicas, List<String> command, List<String> args, Map<String, String> environment,
      List<PortMapping> ports, String workingDirectory) {
    private ContainerDefinition(String resourceName, String containerName, String image,
        int replicas, List<String> command, List<String> args, Map<String, String> environment,
        List<PortMapping> ports) {
      this(resourceName, containerName, image, replicas, command, args, environment, ports, null);
    }

    private Map<String, Object> toMap() {
      Map<String, Object> container = new LinkedHashMap<>();
      container.put("name", containerName);
      container.put("image", image);
      container.put("imagePullPolicy", "IfNotPresent");
      if (workingDirectory != null && !workingDirectory.isBlank()) container.put("workingDir", workingDirectory);
      if (command != null && !command.isEmpty()) container.put("command", command);
      if (args != null && !args.isEmpty()) container.put("args", args);
      if (!environment.isEmpty()) {
        container.put("env", environment.entrySet().stream().map(entry -> Map.of(
            "name", entry.getKey(), "value", entry.getValue())).toList());
      }
      if (!ports.isEmpty()) {
        container.put("ports", ports.stream().map(port -> Map.<String, Object>of(
            "containerPort", port.containerPort(), "protocol", port.protocol())).toList());
      }
      return container;
    }
  }

  public static final class ApplicationDefinitionException extends RuntimeException {
    public ApplicationDefinitionException(String message) {
      super(message);
    }
  }
}
