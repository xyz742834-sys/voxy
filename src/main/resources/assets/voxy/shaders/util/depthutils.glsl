//These defines are definatly going to break some shaders somehow

#ifndef UNDEFINE_DEPTH
#define UNDEFINE_DEPTH

// ⚠⚠ Vulkan 経路では深度規約の define が **必須** である (Phase 5c-4a)。
//
// このフォークの Vulkan 側は「逆Z・0..1」しか持たない [VkDepth の javadoc]。
// 定義を忘れると下の #else に落ち、**黙って非逆Zの定数になる**:
//
//   REDUCTION      min -> max      HiZ が「最も手前」を持ち、見えているノードを落とす (絵に穴)
//   CLOSER_SIGN    +1.0 -> -1.0    深度の clamp が手前ではなく奥へ押しやる
//   NEAR / FAR     反転            「何も描いていない」の判定が全部裏返る
//
// いずれも**落ちないし警告も出ない**。実際に 5c-3a と 5c-4a の両方で、
// 変異を入れると絵だけが静かに壊れることを確かめている。
//
// ⚠ ここに #error を置くのは**やめた**。#error はプリプロセスそのものを失敗させるので、
// VkShader.explainCompileFailure が展開テキストを得られなくなり、
// **他のガードの診断を潰す** (実際に gl_InstanceID の案内が出なくなった)。
// 代わりに VkShader.Builder.compile() が Java 側で弾く — あちらのほうが
// 早く発火し、メッセージも読める。

#ifdef USE_REVERSE_Z
#define REDUCTION min
#define REDUCTION2 max
#define NEAR 1.0f
#define FAR 0.0f
#define CLOSER_SIGN 1.0f
#define DEPTH_SCALAR_COMPARE(a,b) ((a)>(b))
#define DEPTH_SCALAR_COMPARE_EQUAL(a,b) ((a)>=(b))
#else
#define REDUCTION max
#define REDUCTION2 min
#define NEAR 0.0f
#define FAR 1.0f
#define CLOSER_SIGN -1.0f
#define DEPTH_SCALAR_COMPARE(a,b) ((a)<(b))
#define DEPTH_SCALAR_COMPARE_EQUAL(a,b) ((a)<=(b))
#endif



#ifdef USE_ZERO_ONE_DEPTH
vec3 NDC2SCREEN(vec3 val) {
    return vec3(val.xy*0.5f+0.5f, val.z);
}
vec3 SCREEN2NDC(vec3 val) {
    return vec3(val.xy*2.0f-1.0f, val.z);
}
float NDC2SCREEN_DEPTH(float val) {
    return val;
}
float SCREEN2NDC_DEPTH(float val) {
    return val;
}
#else
vec3 NDC2SCREEN(vec3 val) {
    return val*0.5f+0.5f;
}
vec3 SCREEN2NDC(vec3 val) {
    return val*2.0f-1.0f;
}
float NDC2SCREEN_DEPTH(float val) {
    return val*0.5f+0.5f;
}
float SCREEN2NDC_DEPTH(float val) {
    return val*2.0f-1.0f;
}
#endif


#else
#undef UNDEFINE_DEPTH

#undef REDUCTION
#undef REDUCTION2
#undef NEAR
#undef FAR
#undef CLOSER_SIGN
#undef DEPTH_SCALAR_COMPARE
#undef DEPTH_SCALAR_COMPARE_EQUAL
#undef USE_ZERO_ONE_DEPTH

#endif