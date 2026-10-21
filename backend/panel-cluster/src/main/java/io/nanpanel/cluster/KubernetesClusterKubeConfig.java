package io.nanpanel.cluster;

import io.fabric8.kubernetes.api.model.AuthInfo;
import io.fabric8.kubernetes.api.model.Cluster;
import io.fabric8.kubernetes.api.model.Config;
import io.fabric8.kubernetes.api.model.Context;
import io.fabric8.kubernetes.api.model.NamedAuthInfo;
import io.fabric8.kubernetes.api.model.NamedCluster;
import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.api.model.Preferences;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.kubernetes.client.internal.KubeConfigUtils;
import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Validates and narrows an imported kubeconfig to one safe, explicit context. */
public final class KubernetesClusterKubeConfig {
  private static final int MAX_CONFIG_CHARACTERS = 256 * 1024;
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,252}");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");

  private final String contextName;
  private final URI endpoint;
  private final String sanitizedYaml;

  private KubernetesClusterKubeConfig(String contextName, URI endpoint, String sanitizedYaml) {
    this.contextName = contextName;
    this.endpoint = endpoint;
    this.sanitizedYaml = sanitizedYaml;
  }

  public static KubernetesClusterKubeConfig select(String kubeconfig, String contextName) {
    if (kubeconfig == null || kubeconfig.isBlank()
        || kubeconfig.length() > MAX_CONFIG_CHARACTERS) {
      throw invalid("Kubeconfig is empty or exceeds the import limit");
    }
    if (contextName == null || !NAME.matcher(contextName).matches()) {
      throw invalid("A valid kubeconfig context name is required");
    }

    Config source;
    try {
      source = KubeConfigUtils.parseConfigFromString(kubeconfig);
    } catch (RuntimeException failure) {
      throw invalid("Kubeconfig YAML is invalid");
    }
    if (source == null || !"v1".equals(source.getApiVersion())
        || !"Config".equals(source.getKind())) {
      throw invalid("Kubeconfig kind or API version is invalid");
    }

    NamedContext namedContext = unique(source.getContexts(), contextName, "context",
        NamedContext::getName);
    Context context = namedContext.getContext();
    if (context == null || blank(context.getCluster()) || blank(context.getUser())) {
      throw invalid("The selected context must name a cluster and user");
    }
    NamedCluster namedCluster = unique(source.getClusters(), context.getCluster(), "cluster",
        NamedCluster::getName);
    NamedAuthInfo namedUser = unique(source.getUsers(), context.getUser(), "user",
        NamedAuthInfo::getName);
    Cluster cluster = namedCluster.getCluster();
    AuthInfo user = namedUser.getUser();
    if (cluster == null || user == null) {
      throw invalid("The selected cluster and user definitions are required");
    }

    URI endpoint = validateCluster(cluster);
    if (!blank(cluster.getCertificateAuthorityData())
        && !boundedCertificate(cluster.getCertificateAuthorityData())) {
      throw invalid("Embedded Kubernetes CA data exceeds the credential limit");
    }
    validateUser(user);

    Config selected = new Config();
    selected.setApiVersion("v1");
    selected.setKind("Config");
    selected.setCurrentContext(contextName);
    selected.setClusters(List.of(new NamedCluster(cluster, namedCluster.getName())));
    selected.setContexts(List.of(new NamedContext(context, contextName)));
    selected.setUsers(List.of(new NamedAuthInfo(namedUser.getName(), user)));
    selected.setPreferences(new Preferences());
    String sanitized = Serialization.asYaml(selected);
    if (sanitized == null || sanitized.length() > MAX_CONFIG_CHARACTERS) {
      throw invalid("The selected kubeconfig context exceeds the import limit");
    }
    return new KubernetesClusterKubeConfig(contextName, endpoint, sanitized);
  }

  public String contextName() {
    return contextName;
  }

  public URI endpoint() {
    return endpoint;
  }

  /** Returns only the reduced config. Callers must encrypt it before persistence. */
  public String sanitizedYaml() {
    return sanitizedYaml;
  }

  public KubernetesClient openClient() {
    io.fabric8.kubernetes.client.Config config =
        io.fabric8.kubernetes.client.Config.fromKubeconfig(sanitizedYaml);
    config.setConnectionTimeout(3_000);
    config.setRequestTimeout(5_000);
    config.setRequestRetryBackoffLimit(0);
    return new KubernetesClientBuilder().withConfig(config).build();
  }

  @Override
  public String toString() {
    return "KubernetesClusterKubeConfig[contextName=" + contextName + ", endpoint="
        + endpoint + ", credentials=REDACTED]";
  }

  private static URI validateCluster(Cluster cluster) {
    if (Boolean.TRUE.equals(cluster.getInsecureSkipTlsVerify())
        || !blank(cluster.getCertificateAuthority()) || !blank(cluster.getProxyUrl())) {
      throw invalid("TLS verification, file-based CA, and kubeconfig proxies must remain enabled or be embedded");
    }
    if (blank(cluster.getServer())) {
      throw invalid("Kubernetes API endpoint is required");
    }
    URI endpoint;
    try {
      endpoint = URI.create(cluster.getServer());
    } catch (IllegalArgumentException failure) {
      throw invalid("Kubernetes API endpoint is invalid");
    }
    String host = endpoint.getHost();
    if (!"https".equalsIgnoreCase(endpoint.getScheme()) || host == null
        || endpoint.getUserInfo() != null || endpoint.getQuery() != null
        || endpoint.getFragment() != null || unsafeHost(host)) {
      throw invalid("Kubernetes API endpoint must use a verified HTTPS URL");
    }
    return endpoint;
  }

  private static boolean unsafeHost(String hostValue) {
    String host = hostValue.toLowerCase(Locale.ROOT);
    if (host.equals("localhost") || host.endsWith(".localhost")
        || host.equals("metadata") || host.equals("metadata.google.internal")) {
      return true;
    }
    String ipLiteral = host.startsWith("[") && host.endsWith("]")
        ? host.substring(1, host.length() - 1) : host;
    if (ipLiteral.matches("[0-9.]+") || ipLiteral.contains(":")) {
      try {
        InetAddress address = InetAddress.getByName(ipLiteral);
        return address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isMulticastAddress();
      } catch (UnknownHostException failure) {
        return true;
      }
    }
    return false;
  }

  private static void validateUser(AuthInfo user) {
    if (user.getExec() != null || user.getAuthProvider() != null
        || !blank(user.getTokenFile()) || !blank(user.getClientCertificate())
        || !blank(user.getClientKey()) || user.getAs() != null
        || (user.getAsGroups() != null && !user.getAsGroups().isEmpty())
        || (user.getAsUserExtra() != null && !user.getAsUserExtra().isEmpty())
        || !blank(user.getAsUid())) {
      throw invalid("Exec plugins, external credential files, auth providers, and impersonation are unsupported");
    }
    String bearerToken = user.getToken();
    boolean token = !blank(bearerToken);
    boolean basic = boundedCredential(user.getUsername()) && boundedCredential(user.getPassword());
    boolean partialBasic = !blank(user.getUsername()) != !blank(user.getPassword());
    boolean certificate = boundedCertificate(user.getClientCertificateData());
    boolean key = boundedCertificate(user.getClientKeyData());
    if (token && (bearerToken.length() > 16_384 || !TOKEN.matcher(bearerToken).matches())) {
      throw invalid("Kubeconfig bearer token format is invalid");
    }
    if (partialBasic || certificate != key
        || (token ? 1 : 0) + (basic ? 1 : 0) + (certificate && key ? 1 : 0) != 1) {
      throw invalid("Kubeconfig must contain exactly one supported embedded credential method");
    }
  }

  private static <T> T unique(List<T> namedValues, String name, String kind,
                              Function<T, String> nameOf) {
    if (namedValues == null) {
      throw invalid("Kubeconfig " + kind + " definitions are missing");
    }
    T found = null;
    for (T candidate : namedValues) {
      String candidateName = nameOf.apply(candidate);
      if (Objects.equals(candidateName, name)) {
        if (found != null) {
          throw invalid("Kubeconfig contains duplicate " + kind + " names");
        }
        found = candidate;
      }
    }
    if (found == null) {
      throw invalid("The selected kubeconfig " + kind + " does not exist");
    }
    return found;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean boundedCredential(String value) {
    return !blank(value) && value.length() <= 16_384
        && value.chars().noneMatch(Character::isISOControl);
  }

  private static boolean boundedCertificate(String value) {
    return !blank(value) && value.length() <= 96 * 1024;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
