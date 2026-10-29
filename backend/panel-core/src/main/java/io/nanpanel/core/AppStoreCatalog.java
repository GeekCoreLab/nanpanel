package io.nanpanel.core;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Curated in-repo catalog of one-click applications for the App Store.
 * The catalog is a static, versioned manifest: it describes what each app needs
 * (image, default port, named volumes, environment) but performs no deployment
 * and writes nothing. Deploy is orchestrated through the existing typed Helper
 * operations; {@link AppStoreApp#deployableNow()} records whether the current
 * {@code container.deploy} capability (image + single loopback port) can express
 * the app's minimal run without any extension.
 */
public final class AppStoreCatalog {
  private AppStoreCatalog() {}

  public static final int VERSION = 1;

  public record CatalogVolume(String name, String mountPath, String description) {}

  public record CatalogEnvironment(String name, String description, boolean required,
                                   boolean secret) {}

  public record AppStoreApp(
      String id,
      String name,
      String category,
      String summary,
      String image,
      Integer defaultPort,
      List<CatalogVolume> volumes,
      List<CatalogEnvironment> environment,
      boolean deployableNow,
      String deployNote) {}

  private static final List<AppStoreApp> APPS = List.of(
      new AppStoreApp("nginx", "Nginx", "web",
          "高性能 Web 服务器与反向代理", "nginx:1.27-alpine", 80,
          List.of(), List.of(), true, "可直接部署；反向代理与站点规则由网站模块配置。"),
      new AppStoreApp("httpd", "Apache HTTP Server", "web",
          "经典 Apache Web 服务器", "httpd:2.4-alpine", 80,
          List.of(), List.of(), true, "可直接部署；虚拟主机与伪静态规则由网站模块配置。"),
      new AppStoreApp("wordpress", "WordPress", "web",
          "流行博客与内容管理系统（需 MySQL）", "wordpress:6-php8.3-apache", 80,
          List.of(new CatalogVolume("html", "/var/www/html", "站点根目录")),
          List.of(new CatalogEnvironment("WORDPRESS_DB_HOST", "数据库地址", true, false),
              new CatalogEnvironment("WORDPRESS_DB_NAME", "数据库名", true, false),
              new CatalogEnvironment("WORDPRESS_DB_USER", "数据库用户", true, false),
              new CatalogEnvironment("WORDPRESS_DB_PASSWORD", "数据库密码", true, true)),
          false, "需要持久化卷和数据库凭据，待容器操作支持环境变量与数据卷后启用。"),
      new AppStoreApp("mysql", "MySQL", "database",
          "通用关系型数据库", "mysql:8.4", 3306,
          List.of(new CatalogVolume("data", "/var/lib/mysql", "数据目录")),
          List.of(new CatalogEnvironment("MYSQL_ROOT_PASSWORD", "root 密码", true, true),
              new CatalogEnvironment("MYSQL_DATABASE", "初始数据库", false, false)),
          false, "需要持久化卷和 root 密码，待容器操作支持环境变量与数据卷后启用。"),
      new AppStoreApp("mariadb", "MariaDB", "database",
          "MySQL 兼容的开源关系型数据库", "mariadb:11", 3306,
          List.of(new CatalogVolume("data", "/var/lib/mysql", "数据目录")),
          List.of(new CatalogEnvironment("MARIADB_ROOT_PASSWORD", "root 密码", true, true),
              new CatalogEnvironment("MARIADB_DATABASE", "初始数据库", false, false)),
          false, "需要持久化卷和 root 密码，待容器操作支持环境变量与数据卷后启用。"),
      new AppStoreApp("postgresql", "PostgreSQL", "database",
          "功能强大的开源对象关系型数据库", "postgres:17", 5432,
          List.of(new CatalogVolume("data", "/var/lib/postgresql/data", "数据目录")),
          List.of(new CatalogEnvironment("POSTGRES_PASSWORD", "超级用户密码", true, true),
              new CatalogEnvironment("POSTGRES_DB", "初始数据库", false, false)),
          false, "需要持久化卷和密码，待容器操作支持环境变量与数据卷后启用。"),
      new AppStoreApp("redis", "Redis", "caching",
          "高性能内存键值缓存", "redis:7-alpine", 6379,
          List.of(), List.of(), true, "可直接部署（当前为无持久化运行）。"),
      new AppStoreApp("rabbitmq", "RabbitMQ", "messaging",
          "开源消息队列代理（含管理控制台）", "rabbitmq:4-management", 5672,
          List.of(new CatalogVolume("data", "/var/lib/rabbitmq", "队列与状态目录")),
          List.of(new CatalogEnvironment("RABBITMQ_DEFAULT_USER", "默认用户", true, false),
              new CatalogEnvironment("RABBITMQ_DEFAULT_PASS", "默认密码", true, true)),
          false, "需要持久化卷和凭据，待容器操作支持环境变量与数据卷后启用。"),
      new AppStoreApp("minio", "MinIO", "storage",
          "S3 兼容的高性能对象存储", "minio/minio", 9000,
          List.of(new CatalogVolume("data", "/data", "对象数据目录")),
          List.of(new CatalogEnvironment("MINIO_ROOT_USER", "管理用户", true, false),
              new CatalogEnvironment("MINIO_ROOT_PASSWORD", "管理密码", true, true)),
          false, "需要持久化卷和凭据，待容器操作支持环境变量与数据卷后启用。"),
      new AppStoreApp("prometheus", "Prometheus", "monitoring",
          "时序指标采集与告警", "prom/prometheus", 9090,
          List.of(new CatalogVolume("config", "/etc/prometheus", "配置目录"),
              new CatalogVolume("data", "/prometheus", "时序数据目录")),
          List.of(), false, "需要挂载配置与数据卷，待容器操作支持数据卷后启用。"),
      new AppStoreApp("grafana", "Grafana", "monitoring",
          "指标可视化与观测面板", "grafana/grafana", 3000,
          List.of(new CatalogVolume("data", "/var/lib/grafana", "面板与数据源目录")),
          List.of(new CatalogEnvironment("GF_SECURITY_ADMIN_PASSWORD", "管理员密码", false, true)),
          false, "需要持久化卷，待容器操作支持数据卷后启用。"),
      new AppStoreApp("adguardhome", "AdGuard Home", "dns",
          "广告拦截与隐私保护 DNS 服务器", "adguard/adguardhome", 3000,
          List.of(new CatalogVolume("work", "/opt/adguardhome/work", "工作目录"),
              new CatalogVolume("conf", "/opt/adguardhome/conf", "配置目录")),
          List.of(), false, "需要持久化卷，待容器操作支持数据卷后启用。"),
      new AppStoreApp("portainer", "Portainer", "management",
          "容器管理图形界面", "portainer/portainer-ce", 9000,
          List.of(new CatalogVolume("data", "/data", "Portainer 数据目录")),
          List.of(), false, "需要挂载数据卷与 Docker Socket，超出当前容器操作边界。")
  );

  public static List<AppStoreApp> apps() {
    return APPS;
  }

  public static Optional<AppStoreApp> find(String id) {
    if (id == null) return Optional.empty();
    return APPS.stream().filter(app -> app.id().equals(id)).findFirst();
  }

  private static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>();
  static {
    CATEGORY_LABELS.put("web", "Web 与建站");
    CATEGORY_LABELS.put("database", "数据库");
    CATEGORY_LABELS.put("caching", "缓存");
    CATEGORY_LABELS.put("messaging", "消息队列");
    CATEGORY_LABELS.put("storage", "对象存储");
    CATEGORY_LABELS.put("monitoring", "监控运维");
    CATEGORY_LABELS.put("dns", "DNS 与网络");
    CATEGORY_LABELS.put("management", "容器管理");
  }

  public static List<Map<String, Object>> categories() {
    var result = new java.util.ArrayList<Map<String, Object>>();
    for (var entry : CATEGORY_LABELS.entrySet()) {
      long count = APPS.stream().filter(app -> app.category().equals(entry.getKey())).count();
      Map<String, Object> category = new LinkedHashMap<>();
      category.put("id", entry.getKey());
      category.put("label", entry.getValue());
      category.put("count", count);
      result.add(category);
    }
    return result;
  }
}