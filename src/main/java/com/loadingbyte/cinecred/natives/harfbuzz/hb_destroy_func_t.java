// Generated by jextract

package com.loadingbyte.cinecred.natives.harfbuzz;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.foreign.*;
import static jdk.incubator.foreign.CLinker.*;
public interface hb_destroy_func_t {

    void apply(jdk.incubator.foreign.MemoryAddress x0);
    static MemoryAddress allocate(hb_destroy_func_t fi) {
        return RuntimeHelper.upcallStub(hb_destroy_func_t.class, fi, constants$0.hb_destroy_func_t$FUNC, "(Ljdk/incubator/foreign/MemoryAddress;)V");
    }
    static MemoryAddress allocate(hb_destroy_func_t fi, ResourceScope scope) {
        return RuntimeHelper.upcallStub(hb_destroy_func_t.class, fi, constants$0.hb_destroy_func_t$FUNC, "(Ljdk/incubator/foreign/MemoryAddress;)V", scope);
    }
    static hb_destroy_func_t ofAddress(MemoryAddress addr) {
        return (jdk.incubator.foreign.MemoryAddress x0) -> {
            try {
                constants$0.hb_destroy_func_t$MH.invokeExact((Addressable)addr, x0);
            } catch (Throwable ex$) {
                throw new AssertionError("should not reach here", ex$);
            }
        };
    }
}

