package io.github.kiyo29.origami.loader.remap;

/**
 * プラグイン jar から読み込んだクラスのバイト列を、定義する直前に書き換える差し込み口。
 * 対象はプラグイン側のバイトコードだけで、Minecraft 本体には触れない。
 * Phase 3 では何もしない (NONE)。実装は Phase 8 (CraftBukkit 互換クラスのリマップ)。
 */
@FunctionalInterface
public interface ClassRemapper {
    /** 何も変えない実装。 */
    ClassRemapper NONE = (entryPath, classBytes) -> classBytes;

    /**
     * @param entryPath jar 内のパス (例: "com/example/Foo.class")
     * @param classBytes 元のクラスファイル
     * @return 定義に使うクラスファイル (変更しないなら classBytes をそのまま返す)
     */
    byte[] remap(String entryPath, byte[] classBytes);
}
