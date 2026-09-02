# DBeaver WebDAV 备份

这是一个面向 DBeaver 24/25+ 的 Eclipse/OSGi 插件，用于将 DBeaver 项目、脚本和凭据备份到 HTTPS WebDAV 端点。

## 备份范围

插件会创建普通 zip 归档文件，并使用用户名/密码认证上传到 WebDAV。

默认包含：

- 当前 DBeaver 工作空间下的项目，包括项目文件和默认的 `Scripts` 文件夹。
- 当前工作空间 `.metadata/.plugins` 下的关键 Eclipse/DBeaver 元数据，包括资源树、工作台状态和 DBeaver 插件状态，因此脚本、项目可见性和常用偏好可以随工作区恢复。
- `DBeaverData` 下的全局配置目录和常见配置文件，因此会按需求包含 DBeaver 全局设置、AI 等插件配置和凭据。

默认排除：

- 工作空间 `.metadata` 中无关的运行态数据，例如日志、锁文件、临时文件和 Eclipse 本地历史
- `*.log`、`*.lock` 和 `*.tmp` 文件
- 当前工作区之外的其他工作区目录、缓存、日志、临时文件、驱动和安装数据

恢复时会先下载所选 zip 归档，并启动一个独立恢复进程。选择恢复后请关闭 DBeaver，独立进程会在 DBeaver 完全退出后替换匹配的本地目录和文件；等待几秒后再重新打开 DBeaver，以便项目、脚本、偏好配置和凭据干净地重新加载。

## 界面

插件会添加一个 `WebDAV 备份` 主菜单，包含：

- `立即备份`
- `恢复备份...`
- `设置...`

偏好设置页位于 `Window -> Preferences -> WebDAV 备份`，用于保存：

- WebDAV 地址
- 用户名
- 密码，存储在 Eclipse 安全存储中
- 远程目录，默认值为 `dbeaver-backups`
- 保留数量，默认值为 `10`

## 构建

要求：

- JDK 21 或更高版本，用于 Maven/Tycho
- Maven 3.9+

在仓库根目录执行构建：

```bash
mvn clean verify
```

构建成功后，p2 更新站点会被复制到 `dist/`。

## 发布

GitHub Actions 会在提交消息第一行以 `v版本号` 开头时自动构建、打包并发布，例如：

```text
v0.0.1
```

发布构建会根据提交消息中的版本号自动同步 Maven POM、插件 `MANIFEST.MF` 和 feature 版本号，无需手动修改版本文件。

Maven 版本集中在根 `pom.xml` 的 `revision` 属性中，子模块会自动引用该值；本地手动同步版本时请在 Git Bash、WSL 或 Linux/macOS shell 中运行 `scripts/sync-release-version.sh`。

发布流程会将 `dist/` 打包上传到 GitHub Releases，并同步发布到 `gh-pages` 分支作为 GitHub Pages 更新站点。

首次使用前，请在仓库 `Settings -> Pages` 中将 Source 设置为 `Deploy from a branch`，Branch 选择 `gh-pages` 和 `/ (root)`。

插件更新地址：

```text
https://bmqy.github.io/dbeaver-webdav/
```

## DBeaver 升级后插件消失

这个插件通过 Eclipse p2 安装到当前 DBeaver 安装实例中。工作区、连接配置和偏好通常位于用户目录，但插件 JAR 和 p2 安装记录属于 DBeaver 的产品安装目录。即使升级向导每次默认选择同一个目录，安装器也可能先清理并重建该目录中的产品文件，只保留 DBeaver 自带内容，因此第三方插件会消失。这不是 WebDAV 插件运行时把自己删除了，当前更新站点的插件和 feature 标识也保持一致。

Windows 上可以继续使用 DBeaver 安装器升级，但不能保证通过 p2 安装的第三方扩展会随安装器保留；安装器的目录选择只决定新版产品写入哪里，不代表会迁移旧 p2 插件。不要使用“删除旧目录后解压新版 ZIP”的方式升级；DBeaver 官方文档明确要求 ZIP 版本不要覆盖旧目录，这种方式同样需要在新产品目录中重新安装第三方扩展。

目前项目没有提供安装器升级完成后的自动补装方案。如果升级后插件消失，请重新打开 `Help -> Install New Software...`，使用上面的更新地址安装 `DBeaver WebDAV 备份`，然后重启 DBeaver。重新安装只补回插件文件和 p2 注册信息，不会主动删除本插件保存在 `DBeaverData` 中的配置；后续会继续评估更可靠的自动保留或自动补装方案。

## 本地安装

1. 构建项目。
2. 在 DBeaver 中打开 `Help -> Install New Software...`。
3. 选择 `Add...`，使用更新地址 `https://bmqy.github.io/dbeaver-webdav/`；本地调试时也可以选择 `Local...` 并使用生成的 `dist/` 目录。

	![通过链接地址安装](https://image.bmqy.net/upload/2026-07/QQ20260730-162813.png)

4. 选择 `DBeaver WebDAV 备份` 并完成安装。
5. 出现信任提示时，选择信任 `Artifacts` 并继续安装。

	![信任 Artifacts](https://image.bmqy.net/upload/2026-07/QQ20260730-161343.png)

6. 重启 DBeaver。

## 注意事项

- 仅接受 HTTPS WebDAV 端点。
- 备份归档是普通 zip 文件。WebDAV 传输加密依赖 HTTPS。
- 凭据备份是备份范围内的刻意设计；请相应保护 WebDAV 账号和远程目录。
- 对于归档中存在的文件，恢复操作具有破坏性，因为它会覆盖匹配的本地项目、脚本和凭据文件。
