# JiuJiuLeafDecay · 啾啾快速落叶

版本：1.0.0。范围：本次独立编写的完整插件源码，不含其他插件源码或字节码补丁。

目标：用户提供的 Mizuki 26.2-DEV-ver/26.2@f10fd16 / API 26.2-SNAPSHOT。
构建使用本地已核对的 Folia 26.2.build.7-beta API（class major 69）与 Java 25。
不使用 NMS、反射调度、其他业务插件或数据库。兼容 Paper 调度路径，但本次运行验收以 Mizuki 为准。

## 构建

安装 JDK 25，Windows 在工程根目录执行：

```bat
gradlew.bat clean build
```

Linux/macOS：`sh gradlew clean build`。输出为 `build/libs/JiuJiuLeafDecay.jar`。
Wrapper 使用 Gradle 9.7.1，首次需下载 Gradle 和测试依赖（JUnit、Mockito）。
仅编译时可执行 `gradlew.bat jar`；完整构建包含自动测试。

`libraries/` 中的 Folia API 及 Adventure、Guava、SnakeYAML、注解等 JAR 是构建/测试依赖，
来自 D:/Github 内已核对的本地 API 输入，不是安装插件，不打入成品，也不要复制进服务端 plugins。
`gradle/wrapper/gradle-wrapper.jar` 仅为官方构建引导器。

## 实现范围

- 玩家挖掉树干/树叶后，按区块限额检查附近曼哈顿范围；响应自然树叶物理更新与自然落叶。
- 始终跳过 persistent=true 的永久树叶，且只对原版 distance 达到最大值的自然树叶调用 randomTick。
- 原生事件、掉落、游戏规则由服务端执行，不手工发物品，不强行更改树叶距离。
- 区块与任务有上限，坐标扫描分 tick 执行；不扫描全世界，不主动加载区块。
- 独立区块任务句柄；结束/卸载/重载/停用清理，超时回收仅处理队列元数据。
- `/jjleaf status` 查看队列；`/jjleaf reload` 异步读配置。默认管理权限 `jiujiuleafdecay.admin`（OP）。

后台功能使用命令管理，没有新增玩家 GUI 或 CE 资源。配置注释说明范围与默认值。
已有世界编辑器生成的装饰若 persistent=false，无法仅靠摆放外观判断归属，应先正确设置永久属性。
超额、超时、未加载区块或区域归属不足时停止该部分加速，原版自然行为继续。
