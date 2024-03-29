// Generated by jextract

package com.loadingbyte.cinecred.natives.zimg;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.foreign.*;
import static jdk.incubator.foreign.CLinker.*;
public class zimg_image_buffer {

    static final MemoryLayout $struct$LAYOUT = MemoryLayout.structLayout(
        C_INT.withName("version"),
        MemoryLayout.paddingLayout(32),
        MemoryLayout.sequenceLayout(4, MemoryLayout.structLayout(
            C_POINTER.withName("data"),
            C_LONG_LONG.withName("stride"),
            C_INT.withName("mask"),
            MemoryLayout.paddingLayout(32)
        )).withName("plane")
    ).withName("zimg_image_buffer");
    public static MemoryLayout $LAYOUT() {
        return zimg_image_buffer.$struct$LAYOUT;
    }
    static final VarHandle version$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("version"));
    public static VarHandle version$VH() {
        return zimg_image_buffer.version$VH;
    }
    public static int version$get(MemorySegment seg) {
        return (int)zimg_image_buffer.version$VH.get(seg);
    }
    public static void version$set( MemorySegment seg, int x) {
        zimg_image_buffer.version$VH.set(seg, x);
    }
    public static int version$get(MemorySegment seg, long index) {
        return (int)zimg_image_buffer.version$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void version$set(MemorySegment seg, long index, int x) {
        zimg_image_buffer.version$VH.set(seg.asSlice(index*sizeof()), x);
    }
    public static MemorySegment plane$slice(MemorySegment seg) {
        return seg.asSlice(8, 96);
    }
    public static long sizeof() { return $LAYOUT().byteSize(); }
    public static MemorySegment allocate(SegmentAllocator allocator) { return allocator.allocate($LAYOUT()); }
    public static MemorySegment allocate(ResourceScope scope) { return allocate(SegmentAllocator.ofScope(scope)); }
    public static MemorySegment allocateArray(int len, SegmentAllocator allocator) {
        return allocator.allocate(MemoryLayout.sequenceLayout(len, $LAYOUT()));
    }
    public static MemorySegment allocateArray(int len, ResourceScope scope) {
        return allocateArray(len, SegmentAllocator.ofScope(scope));
    }
    public static MemorySegment ofAddress(MemoryAddress addr, ResourceScope scope) { return RuntimeHelper.asArray(addr, $LAYOUT(), 1, scope); }
}


