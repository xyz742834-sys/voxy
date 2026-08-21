#define SENTINAL_OUT_OF_BOUNDS uint(-1)

//Which traversal iteration this dispatch is for. Changes every iteration of the dispatch loop
//in HierarchicalOcclusionTraverser.doTraversal, so it is per-dispatch state, not per-frame.
//
//Vulkan GLSL forbids default-block uniforms, so it becomes a push constant there. The block is
//declared without an instance name so the member is still referenced as plain `queueIdx` on both
//backends -- the five use sites below are identical either way.
//See docs/phase2-glsl-compat.md (T-4) and docs/phase2-pushconstant-todo.md.
#ifdef VULKAN
layout(push_constant) uniform TraversalPushConstants {
    uint queueIdx;
};
#else
layout(location = NODE_QUEUE_INDEX_BINDING) uniform uint queueIdx;
#endif

layout(binding = NODE_QUEUE_META_BINDING, std430) restrict buffer NodeQueueMeta {
    uvec4 nodeQueueMetadata[MAX_ITERATIONS];
};

layout(binding = NODE_QUEUE_SOURCE_BINDING, std430) restrict readonly buffer NodeQueueSource {
    uint[] nodeQueueSource;
};

layout(binding = NODE_QUEUE_SINK_BINDING, std430) restrict writeonly buffer NodeQueueSink {
    uint[] nodeQueueSink;
};

#ifdef VULKAN
// ⚠⚠ Vulkan 経路だけ**キューの範囲外アクセスを塞ぐ** (Phase 5c-4)。
//
// 上流のこのファイルには `//TODO: limit the size/writing out of bounds` が残っている。
// 予約の添字は「際限なく増える atomic カウンタ」から来るので、
// キューが埋まると**範囲外に読み書きする**。GL でも Vulkan でも未定義動作である。
//
// GL 側には入れない。参照仕様の値打ちは「上流が何をしているか」を正確に示すことであり、
// この環境では GL 版を走らせられないので**入れても壊していないことを確かめられない**。
// 上流への報告候補に挙げてある [docs/upstream-issue-candidates.md]。
//
// ⚠ 当たった回数を**数える**。黙って切り捨てると
// 「描かれない」と「選ばれなかった」が区別できなくなる [規約 18]。
layout(binding = TRAVERSAL_LIMITS_BINDING, std430) restrict buffer TraversalLimits {
    uint droppedNodePushes;   // シンクが満杯で捨てたノード数
    uint droppedNodeReads;    // ソースの外を指した読み出し
    uint limitsPad0;
    uint limitsPad1;
};
#endif

uint getCurrentNode() {
    if (nodeQueueMetadata[queueIdx].w <= gl_GlobalInvocationID.x) {
        return SENTINAL_OUT_OF_BOUNDS;
    }
#ifdef VULKAN
    // ⚠ メタデータの個数は**溢れても増え続ける**ので、それだけでは境界にならない。
    // 実際に確保されている長さで抑える。捨てた分は書かれていないので読んではならない
    if (gl_GlobalInvocationID.x >= nodeQueueSource.length()) {
        atomicAdd(droppedNodeReads, 1);
        return SENTINAL_OUT_OF_BOUNDS;
    }
#endif
    return nodeQueueSource[gl_GlobalInvocationID.x];
}


//TODO: limit the size/writing out of bounds
//  ⚠ Vulkan 側では pushNode に上限を入れてある (下)。GL 側は上流のまま。
uint nodePushIndex = -1;
void pushNodesInit(uint nodeCount) {
    //Debug
    #ifdef DEBUG
    if (queueIdx >= (MAX_ITERATIONS-1)) {
        printf("LOG: Traversal tried inserting a node into next iteration, which is outside max iteration bounds. GID: %d, count: %d", gl_GlobalInvocationID.x, nodeCount);
        nodePushIndex = -1;
        return;
    }
    #endif

    uint index = atomicAdd(nodeQueueMetadata[queueIdx+1].w, nodeCount);
    //Increment first metadata value if it changes threash hold
    uint inc = ((index+LOCAL_SIZE)>>LOCAL_SIZE_BITS)-(index>>LOCAL_SIZE_BITS);
    atomicAdd(nodeQueueMetadata[queueIdx+1].x, inc);//TODO: see if making this conditional on inc != 0 is faster
    nodePushIndex = index;
}

void pushNode(uint nodeId) {
    #ifdef DEBUG
    if (nodePushIndex == -1) {
        printf("LOG: Tried pushing node when push node wasnt successful. GID: %d, pushing: %d", gl_GlobalInvocationID.x, nodeId);
        return;
    }
    #endif
#ifdef VULKAN
    // ⚠ 予約の添字は際限なく増える atomic カウンタから来る。**ここで止めなければ範囲外に書く**
    if (nodePushIndex == uint(-1)) {
        return;                       // 予約に失敗している。溢れとは別の状態なので数えない
    }
    if (nodePushIndex >= nodeQueueSink.length()) {
        atomicAdd(droppedNodePushes, 1);
        return;
    }
#endif
    nodeQueueSink[nodePushIndex++] = nodeId;
}

#define SIMPLE_QUEUE(type, name, bindingIndex) layout(binding = bindingIndex, std430) restrict buffer name##Struct { \
    type name##Index; \
    type##[] name; \
};