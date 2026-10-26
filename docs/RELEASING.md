# Folia 26.3 构建与发布

## 本次修复

- 区域调度保留真实区块坐标，在区块所属 Folia 区域中序列化运行中的区块。
- 关服时保存自定义方块与容器数据，保存失败保留待写入快照并报告错误。
- 增加可选材质路径混淆，更新 JSON 引用、动画与 overlay；保留源资源文件。
- 已在独立 Folia 26.3 build 5 alpha、Java 25 环境验证关服重启、容器内容、作物与资源包生成。

Folia 26.3 使用 `craft-engine-paper-plugin-<版本>.jar`。发行版中的 Bukkit 与 Paper JAR 二选一安装。

材质混淆默认关闭，在 `config.yml` 中启用后重新生成资源包：

```yaml
resource-pack:
  texture-obfuscation:
    enable: true
    seed: 0
    exclude: []
```

混淆用于隐藏自定义方块、物品材质的语义路径；客户端需要读取资源包，因此它不能阻止材质被提取。

## 本地构建

需要 JDK 25。编译器读取 26.3 API，插件字节码仍以 Java 21 为目标。
Gradle Wrapper 固定为 9.8.1，并校验下载文件的 SHA-256。

```bash
./gradlew :core:test :bukkit:paper-loader:shadowJar :bukkit:loader:shadowJar \
  --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3G
```

插件输出到 `target/`。FarmersDelight 的源码构建还需要重定位后的 Adventure 依赖，可用以下任务生成，无需启动服务器：

```bash
./gradlew :core:adventure:shadowJar --no-daemon -Dorg.gradle.jvmargs=-Xmx3G
```

该依赖位于 `core/adventure/build/libs/`，可作为 FarmersDelight 的 `-PceLibraries` 目录。

## tag 自动发布

`.github/workflows/ci.yml` 对 push、pull request 和手动运行执行测试与插件构建。
只有推送 `v*` tag 才发布 GitHub Release，且 tag 必须精确匹配 `gradle.properties` 的 `project_version`。

例如当前版本：

```bash
git tag -a v26.9.2-folia26.3.1 -m "Folia 26.3 compatibility and persistence fixes"
git push origin main
git push origin v26.9.2-folia26.3.1
```

测试通过后，CI 上传两个插件 JAR、`SHA256SUMS.txt` 和本文。发布使用仓库自带的 `GITHUB_TOKEN`，无需额外 token；只有发布 job 申请 `contents: write`。
已存在的 Release 可通过重新运行工作流更新附件。下载后可运行 `sha256sum --check SHA256SUMS.txt` 校验。
后续发布先更新源码版本并提交，再推送对应的新 tag。

官方说明：[Java 安装与缓存](https://github.com/actions/setup-java)、[Gradle Java 兼容表](https://docs.gradle.org/current/userguide/compatibility.html)、[GitHub CLI 发布命令](https://cli.github.com/manual/gh_release_create)。
