package com.github.jaiimageio.impl.common;

import com.github.jaiimageio.jpeg2000.J2KImageReadParam;

public class PackageUtil {

    private static String version;
    private static String vendor;
    private static String specTitle;

    static {
        Package pkg = J2KImageReadParam.class.getPackage();
        version = pkg.getImplementationVersion();
        vendor = pkg.getImplementationVendor();
        specTitle = pkg.getSpecificationTitle();
        // If we shaded the J2K library, the MANIFEST info is lost.
        if (version == null) version = "?";
        if (vendor == null) vendor = "?";
        if (specTitle == null) specTitle = "?";
    }

    public static String getVersion() {
        return version;
    }

    public static String getVendor() {
        return vendor;
    }

    public static String getSpecificationTitle() {
        return specTitle;
    }

}
