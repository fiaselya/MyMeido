# ⚠️ 骨架占位 —— 尚未注册进 settings.gradle，目前不参与构建
#
# 这是 neoforge-1.21.1 节点的骨架（多加载器：与 versions/1.21.1 共用 MC 1.21.1）。
# 多加载器注册方式：项目名和逻辑版本要分开写（否则条件注释会把
# "1.21.1-neoforge" 当成 1.21.1 的 pre-release 比较），并把 NeoForge 节点
# 指向单独的构建脚本（build.gradle 是 Fabric Loom 的，NeoForge 得用
# NeoGradle / ModDevGradle，官方模板见 stonecutter-versioning 的 multiloader 模板）：
#
#   settings.gradle:
#     versions '1.21.1'
#     versions 'neoforge-1.21.1': '1.21.1'   // 项目名: 逻辑版本
#
# 注册前必须先做：
#   1. 选 NeoForge 的构建插件（ModDevGradle）并接到单独的 build-neoforge.gradle
#   2. 把 net/ 源码里 Yarn 名（ItemStack#getStack 等那套 Yarn 词汇）迁移到
#      NeoForge 官方 mappings（Mojang 名）—— 这是真正的移植工作量所在
#   3. fabric.mod.json → neoforge.mods.toml、注册入口、网络包 API 全换
#   4. 跑通构建 + 冒烟（冒烟脚本里的 /mymeido 指令断言同样适用于 NeoForge 服务端）
#
# 代码共享同一份根 src/，版本差异用 Stonecutter 条件注释处理。
minecraft_version=1.21.1
neoforge_version=填入 21.1.x 对应的 neoforge 版本
