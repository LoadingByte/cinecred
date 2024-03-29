// Generated by jextract

package com.loadingbyte.cinecred.natives.zimg;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.foreign.*;
import static jdk.incubator.foreign.CLinker.*;
public class zimg_image_format {

    static final MemoryLayout $struct$LAYOUT = MemoryLayout.structLayout(
        C_INT.withName("version"),
        C_INT.withName("width"),
        C_INT.withName("height"),
        C_INT.withName("pixel_type"),
        C_INT.withName("subsample_w"),
        C_INT.withName("subsample_h"),
        C_INT.withName("color_family"),
        C_INT.withName("matrix_coefficients"),
        C_INT.withName("transfer_characteristics"),
        C_INT.withName("color_primaries"),
        C_INT.withName("depth"),
        C_INT.withName("pixel_range"),
        C_INT.withName("field_parity"),
        C_INT.withName("chroma_location"),
        MemoryLayout.structLayout(
            C_DOUBLE.withName("left"),
            C_DOUBLE.withName("top"),
            C_DOUBLE.withName("width"),
            C_DOUBLE.withName("height")
        ).withName("active_region"),
        C_INT.withName("alpha"),
        MemoryLayout.paddingLayout(32)
    ).withName("zimg_image_format");
    public static MemoryLayout $LAYOUT() {
        return zimg_image_format.$struct$LAYOUT;
    }
    static final VarHandle version$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("version"));
    public static VarHandle version$VH() {
        return zimg_image_format.version$VH;
    }
    public static int version$get(MemorySegment seg) {
        return (int)zimg_image_format.version$VH.get(seg);
    }
    public static void version$set( MemorySegment seg, int x) {
        zimg_image_format.version$VH.set(seg, x);
    }
    public static int version$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.version$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void version$set(MemorySegment seg, long index, int x) {
        zimg_image_format.version$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle width$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("width"));
    public static VarHandle width$VH() {
        return zimg_image_format.width$VH;
    }
    public static int width$get(MemorySegment seg) {
        return (int)zimg_image_format.width$VH.get(seg);
    }
    public static void width$set( MemorySegment seg, int x) {
        zimg_image_format.width$VH.set(seg, x);
    }
    public static int width$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.width$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void width$set(MemorySegment seg, long index, int x) {
        zimg_image_format.width$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle height$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("height"));
    public static VarHandle height$VH() {
        return zimg_image_format.height$VH;
    }
    public static int height$get(MemorySegment seg) {
        return (int)zimg_image_format.height$VH.get(seg);
    }
    public static void height$set( MemorySegment seg, int x) {
        zimg_image_format.height$VH.set(seg, x);
    }
    public static int height$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.height$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void height$set(MemorySegment seg, long index, int x) {
        zimg_image_format.height$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle pixel_type$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("pixel_type"));
    public static VarHandle pixel_type$VH() {
        return zimg_image_format.pixel_type$VH;
    }
    public static int pixel_type$get(MemorySegment seg) {
        return (int)zimg_image_format.pixel_type$VH.get(seg);
    }
    public static void pixel_type$set( MemorySegment seg, int x) {
        zimg_image_format.pixel_type$VH.set(seg, x);
    }
    public static int pixel_type$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.pixel_type$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void pixel_type$set(MemorySegment seg, long index, int x) {
        zimg_image_format.pixel_type$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle subsample_w$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("subsample_w"));
    public static VarHandle subsample_w$VH() {
        return zimg_image_format.subsample_w$VH;
    }
    public static int subsample_w$get(MemorySegment seg) {
        return (int)zimg_image_format.subsample_w$VH.get(seg);
    }
    public static void subsample_w$set( MemorySegment seg, int x) {
        zimg_image_format.subsample_w$VH.set(seg, x);
    }
    public static int subsample_w$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.subsample_w$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void subsample_w$set(MemorySegment seg, long index, int x) {
        zimg_image_format.subsample_w$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle subsample_h$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("subsample_h"));
    public static VarHandle subsample_h$VH() {
        return zimg_image_format.subsample_h$VH;
    }
    public static int subsample_h$get(MemorySegment seg) {
        return (int)zimg_image_format.subsample_h$VH.get(seg);
    }
    public static void subsample_h$set( MemorySegment seg, int x) {
        zimg_image_format.subsample_h$VH.set(seg, x);
    }
    public static int subsample_h$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.subsample_h$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void subsample_h$set(MemorySegment seg, long index, int x) {
        zimg_image_format.subsample_h$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle color_family$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("color_family"));
    public static VarHandle color_family$VH() {
        return zimg_image_format.color_family$VH;
    }
    public static int color_family$get(MemorySegment seg) {
        return (int)zimg_image_format.color_family$VH.get(seg);
    }
    public static void color_family$set( MemorySegment seg, int x) {
        zimg_image_format.color_family$VH.set(seg, x);
    }
    public static int color_family$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.color_family$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void color_family$set(MemorySegment seg, long index, int x) {
        zimg_image_format.color_family$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle matrix_coefficients$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("matrix_coefficients"));
    public static VarHandle matrix_coefficients$VH() {
        return zimg_image_format.matrix_coefficients$VH;
    }
    public static int matrix_coefficients$get(MemorySegment seg) {
        return (int)zimg_image_format.matrix_coefficients$VH.get(seg);
    }
    public static void matrix_coefficients$set( MemorySegment seg, int x) {
        zimg_image_format.matrix_coefficients$VH.set(seg, x);
    }
    public static int matrix_coefficients$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.matrix_coefficients$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void matrix_coefficients$set(MemorySegment seg, long index, int x) {
        zimg_image_format.matrix_coefficients$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle transfer_characteristics$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("transfer_characteristics"));
    public static VarHandle transfer_characteristics$VH() {
        return zimg_image_format.transfer_characteristics$VH;
    }
    public static int transfer_characteristics$get(MemorySegment seg) {
        return (int)zimg_image_format.transfer_characteristics$VH.get(seg);
    }
    public static void transfer_characteristics$set( MemorySegment seg, int x) {
        zimg_image_format.transfer_characteristics$VH.set(seg, x);
    }
    public static int transfer_characteristics$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.transfer_characteristics$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void transfer_characteristics$set(MemorySegment seg, long index, int x) {
        zimg_image_format.transfer_characteristics$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle color_primaries$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("color_primaries"));
    public static VarHandle color_primaries$VH() {
        return zimg_image_format.color_primaries$VH;
    }
    public static int color_primaries$get(MemorySegment seg) {
        return (int)zimg_image_format.color_primaries$VH.get(seg);
    }
    public static void color_primaries$set( MemorySegment seg, int x) {
        zimg_image_format.color_primaries$VH.set(seg, x);
    }
    public static int color_primaries$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.color_primaries$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void color_primaries$set(MemorySegment seg, long index, int x) {
        zimg_image_format.color_primaries$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle depth$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("depth"));
    public static VarHandle depth$VH() {
        return zimg_image_format.depth$VH;
    }
    public static int depth$get(MemorySegment seg) {
        return (int)zimg_image_format.depth$VH.get(seg);
    }
    public static void depth$set( MemorySegment seg, int x) {
        zimg_image_format.depth$VH.set(seg, x);
    }
    public static int depth$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.depth$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void depth$set(MemorySegment seg, long index, int x) {
        zimg_image_format.depth$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle pixel_range$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("pixel_range"));
    public static VarHandle pixel_range$VH() {
        return zimg_image_format.pixel_range$VH;
    }
    public static int pixel_range$get(MemorySegment seg) {
        return (int)zimg_image_format.pixel_range$VH.get(seg);
    }
    public static void pixel_range$set( MemorySegment seg, int x) {
        zimg_image_format.pixel_range$VH.set(seg, x);
    }
    public static int pixel_range$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.pixel_range$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void pixel_range$set(MemorySegment seg, long index, int x) {
        zimg_image_format.pixel_range$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle field_parity$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("field_parity"));
    public static VarHandle field_parity$VH() {
        return zimg_image_format.field_parity$VH;
    }
    public static int field_parity$get(MemorySegment seg) {
        return (int)zimg_image_format.field_parity$VH.get(seg);
    }
    public static void field_parity$set( MemorySegment seg, int x) {
        zimg_image_format.field_parity$VH.set(seg, x);
    }
    public static int field_parity$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.field_parity$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void field_parity$set(MemorySegment seg, long index, int x) {
        zimg_image_format.field_parity$VH.set(seg.asSlice(index*sizeof()), x);
    }
    static final VarHandle chroma_location$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("chroma_location"));
    public static VarHandle chroma_location$VH() {
        return zimg_image_format.chroma_location$VH;
    }
    public static int chroma_location$get(MemorySegment seg) {
        return (int)zimg_image_format.chroma_location$VH.get(seg);
    }
    public static void chroma_location$set( MemorySegment seg, int x) {
        zimg_image_format.chroma_location$VH.set(seg, x);
    }
    public static int chroma_location$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.chroma_location$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void chroma_location$set(MemorySegment seg, long index, int x) {
        zimg_image_format.chroma_location$VH.set(seg.asSlice(index*sizeof()), x);
    }
    public static class active_region {

        static final MemoryLayout active_region$struct$LAYOUT = MemoryLayout.structLayout(
            C_DOUBLE.withName("left"),
            C_DOUBLE.withName("top"),
            C_DOUBLE.withName("width"),
            C_DOUBLE.withName("height")
        );
        public static MemoryLayout $LAYOUT() {
            return active_region.active_region$struct$LAYOUT;
        }
        static final VarHandle left$VH = active_region$struct$LAYOUT.varHandle(double.class, MemoryLayout.PathElement.groupElement("left"));
        public static VarHandle left$VH() {
            return active_region.left$VH;
        }
        public static double left$get(MemorySegment seg) {
            return (double)active_region.left$VH.get(seg);
        }
        public static void left$set( MemorySegment seg, double x) {
            active_region.left$VH.set(seg, x);
        }
        public static double left$get(MemorySegment seg, long index) {
            return (double)active_region.left$VH.get(seg.asSlice(index*sizeof()));
        }
        public static void left$set(MemorySegment seg, long index, double x) {
            active_region.left$VH.set(seg.asSlice(index*sizeof()), x);
        }
        static final VarHandle top$VH = active_region$struct$LAYOUT.varHandle(double.class, MemoryLayout.PathElement.groupElement("top"));
        public static VarHandle top$VH() {
            return active_region.top$VH;
        }
        public static double top$get(MemorySegment seg) {
            return (double)active_region.top$VH.get(seg);
        }
        public static void top$set( MemorySegment seg, double x) {
            active_region.top$VH.set(seg, x);
        }
        public static double top$get(MemorySegment seg, long index) {
            return (double)active_region.top$VH.get(seg.asSlice(index*sizeof()));
        }
        public static void top$set(MemorySegment seg, long index, double x) {
            active_region.top$VH.set(seg.asSlice(index*sizeof()), x);
        }
        static final VarHandle width$VH = active_region$struct$LAYOUT.varHandle(double.class, MemoryLayout.PathElement.groupElement("width"));
        public static VarHandle width$VH() {
            return active_region.width$VH;
        }
        public static double width$get(MemorySegment seg) {
            return (double)active_region.width$VH.get(seg);
        }
        public static void width$set( MemorySegment seg, double x) {
            active_region.width$VH.set(seg, x);
        }
        public static double width$get(MemorySegment seg, long index) {
            return (double)active_region.width$VH.get(seg.asSlice(index*sizeof()));
        }
        public static void width$set(MemorySegment seg, long index, double x) {
            active_region.width$VH.set(seg.asSlice(index*sizeof()), x);
        }
        static final VarHandle height$VH = active_region$struct$LAYOUT.varHandle(double.class, MemoryLayout.PathElement.groupElement("height"));
        public static VarHandle height$VH() {
            return active_region.height$VH;
        }
        public static double height$get(MemorySegment seg) {
            return (double)active_region.height$VH.get(seg);
        }
        public static void height$set( MemorySegment seg, double x) {
            active_region.height$VH.set(seg, x);
        }
        public static double height$get(MemorySegment seg, long index) {
            return (double)active_region.height$VH.get(seg.asSlice(index*sizeof()));
        }
        public static void height$set(MemorySegment seg, long index, double x) {
            active_region.height$VH.set(seg.asSlice(index*sizeof()), x);
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

    public static MemorySegment active_region$slice(MemorySegment seg) {
        return seg.asSlice(56, 32);
    }
    static final VarHandle alpha$VH = $struct$LAYOUT.varHandle(int.class, MemoryLayout.PathElement.groupElement("alpha"));
    public static VarHandle alpha$VH() {
        return zimg_image_format.alpha$VH;
    }
    public static int alpha$get(MemorySegment seg) {
        return (int)zimg_image_format.alpha$VH.get(seg);
    }
    public static void alpha$set( MemorySegment seg, int x) {
        zimg_image_format.alpha$VH.set(seg, x);
    }
    public static int alpha$get(MemorySegment seg, long index) {
        return (int)zimg_image_format.alpha$VH.get(seg.asSlice(index*sizeof()));
    }
    public static void alpha$set(MemorySegment seg, long index, int x) {
        zimg_image_format.alpha$VH.set(seg.asSlice(index*sizeof()), x);
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


