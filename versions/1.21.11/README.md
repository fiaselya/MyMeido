# ⚠️ 骨架占位 —— 尚未注册进 settings.gradle，目前不参与构建
#
# 这是 1.21.11（Fabric）节点的骨架。注册前必须先做：
#   1. 查 1.21.11 的 yarn mappings 版本（https://fabricmc.net/develop/）
#   2. 查对应版本的 Fabric API 版本
#   3. 代码里过一遍 1.21.1 → 1.21.11 的 API 变化（Yarn 名改动、注册流程变化等）
#   4. 到 settings.gradle 的 stonecutter 块里注册：
#        versions '1.21.1', '1.21.11'
#   5. 跑 ./gradlew build + tools/ 冒烟脚本验证
#
# 代码不用复制：Stonecutter 的共享 src/ 在工程根，注册后这个节点直接编译根 src/，
# 有版本差异的地方用 Stonecutter 条件注释（//? if >=1.21.11 { ... //?}）处理。
minecraft_version=1.21.11
yarn_mappings=填入 1.21.11 的 yarn 版本
loader_version=0.19.5
fabric_version=填入 1.21.11 的 fabric api 版本
