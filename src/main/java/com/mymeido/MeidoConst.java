package com.mymeido;

import net.minecraft.util.Identifier;

/**
 * mymeido 全局常量。
 *
 * <p>放在主源集：服务端与客户端都要用。
 */
public final class MeidoConst {

    /** 模组 ID。必须与 fabric.mod.json 的 id、以及所有资源路径的命名空间一致。 */
    public static final String MOD_ID = "mymeido";

    private MeidoConst() {
    }

    /** 生成本模组命名空间下的 Identifier。 */
    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}
