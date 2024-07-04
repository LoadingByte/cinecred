/*
 * $RCSfile: ImageUtil.java,v $
 *
 *
 * Copyright (c) 2005 Sun Microsystems, Inc. All  Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * - Redistribution of source code must retain the above copyright
 *   notice, this  list of conditions and the following disclaimer.
 *
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in
 *   the documentation and/or other materials provided with the
 *   distribution.
 *
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * This software is provided "AS IS," without a warranty of any
 * kind. ALL EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND
 * WARRANTIES, INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY
 * EXCLUDED. SUN MIDROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL
 * NOT BE LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF
 * USING, MODIFYING OR DISTRIBUTING THIS SOFTWARE OR ITS
 * DERIVATIVES. IN NO EVENT WILL SUN OR ITS LICENSORS BE LIABLE FOR
 * ANY LOST REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL,
 * CONSEQUENTIAL, INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND
 * REGARDLESS OF THE THEORY OF LIABILITY, ARISING OUT OF THE USE OF OR
 * INABILITY TO USE THIS SOFTWARE, EVEN IF SUN HAS BEEN ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGES.
 *
 * You acknowledge that this software is not designed or intended for
 * use in the design, construction, operation or maintenance of any
 * nuclear facility.
 *
 * $Revision: 1.7 $
 * $Date: 2007/08/28 18:45:06 $
 * $State: Exp $
 */
package com.github.jaiimageio.impl.common;

import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;
//import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.spi.ImageReaderWriterSpi;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.ImageReadParam;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.IIOException;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;

public class ImageUtil {
    /**
     * Creates a <code>ColorModel</code> that may be used with the
     * specified <code>SampleModel</code>.  If a suitable
     * <code>ColorModel</code> cannot be found, this method returns
     * <code>null</code>.
     *
     * <p> Suitable <code>ColorModel</code>s are guaranteed to exist
     * for all instances of <code>ComponentSampleModel</code>.
     * For 1- and 3- banded <code>SampleModel</code>s, the returned
     * <code>ColorModel</code> will be opaque.  For 2- and 4-banded
     * <code>SampleModel</code>s, the output will use alpha transparency
     * which is not premultiplied.  1- and 2-banded data will use a
     * grayscale <code>ColorSpace</code>, and 3- and 4-banded data a sRGB
     * <code>ColorSpace</code>. Data with 5 or more bands will have a
     * <code>BogusColorSpace</code>.</p>
     *
     * <p>An instance of <code>DirectColorModel</code> will be created for
     * instances of <code>SinglePixelPackedSampleModel</code> with no more
     * than 4 bands.</p>
     *
     * <p>An instance of <code>IndexColorModel</code> will be created for
     * instances of <code>MultiPixelPackedSampleModel</code>. The colormap
     * will be a grayscale ramp with <code>1&nbsp;<<&nbsp;numberOfBits</code>
     * entries ranging from zero to at most 255.</p>
     *
     * @return An instance of <code>ColorModel</code> that is suitable for
     *         the supplied <code>SampleModel</code>, or <code>null</code>.
     *
     * @throws IllegalArgumentException  If <code>sampleModel</code> is
     *         <code>null</code>.
     */
    public static final ColorModel createColorModel(SampleModel sampleModel) {
        // Check the parameter.
        if(sampleModel == null) {
            throw new IllegalArgumentException("sampleModel == null!");
        }

        // Get the data type.
        int dataType = sampleModel.getDataType();

        // Check the data type
        switch(dataType) {
        case DataBuffer.TYPE_BYTE:
        case DataBuffer.TYPE_USHORT:
        case DataBuffer.TYPE_SHORT:
        case DataBuffer.TYPE_INT:
        case DataBuffer.TYPE_FLOAT:
        case DataBuffer.TYPE_DOUBLE:
            break;
        default:
            // Return null for other types.
            return null;
        }

        // The return variable.
        ColorModel colorModel = null;

        // Get the sample size.
        int[] sampleSize = sampleModel.getSampleSize();

        // Create a Component ColorModel.
        if(sampleModel instanceof ComponentSampleModel) {
            // Get the number of bands.
            int numBands = sampleModel.getNumBands();

            // Determine the color space.
            ColorSpace colorSpace = null;
            if(numBands <= 2) {
                colorSpace = ColorSpace.getInstance(ColorSpace.CS_GRAY);
            } else if(numBands <= 4) {
                colorSpace = ColorSpace.getInstance(ColorSpace.CS_sRGB);
            }

            boolean hasAlpha = (numBands == 2) || (numBands == 4);
            boolean isAlphaPremultiplied = false;
            int transparency = hasAlpha ?
                Transparency.TRANSLUCENT : Transparency.OPAQUE;

            colorModel = new ComponentColorModel(colorSpace,
                                                 sampleSize,
                                                 hasAlpha,
                                                 isAlphaPremultiplied,
                                                 transparency,
                                                 dataType);
        } else if (sampleModel.getNumBands() <= 4 &&
                   sampleModel instanceof SinglePixelPackedSampleModel) {
            SinglePixelPackedSampleModel sppsm =
                (SinglePixelPackedSampleModel)sampleModel;

            int[] bitMasks = sppsm.getBitMasks();
            int rmask = 0;
            int gmask = 0;
            int bmask = 0;
            int amask = 0;

            int numBands = bitMasks.length;
            if (numBands <= 2) {
                rmask = gmask = bmask = bitMasks[0];
                if (numBands == 2) {
                    amask = bitMasks[1];
                }
            } else {
                rmask = bitMasks[0];
                gmask = bitMasks[1];
                bmask = bitMasks[2];
                if (numBands == 4) {
                    amask = bitMasks[3];
                }
            }

            int bits = 0;
            for (int i = 0; i < sampleSize.length; i++) {
                bits += sampleSize[i];
            }

            return new DirectColorModel(bits, rmask, gmask, bmask, amask);

        } else if(sampleModel instanceof MultiPixelPackedSampleModel) {
            // Load the colormap with a ramp.
            int bitsPerSample = sampleSize[0];
            int numEntries = 1 << bitsPerSample;
            byte[] map = new byte[numEntries];
            for (int i = 0; i < numEntries; i++) {
                map[i] = (byte)(i*255/(numEntries - 1));
            }

            colorModel = new IndexColorModel(bitsPerSample, numEntries,
                                             map, map, map);

        }

        return colorModel;
    }

    /**
     * Copies data into the packed array of the <code>Raster</code>
     * from an array of unpacked data of the form returned by
     * <code>getUnpackedBinaryData()</code>.
     *
     * <p> If the data are binary, then the target bit will be set if
     * and only if the corresponding byte is non-zero.
     *
     * @throws IllegalArgumentException if <code>isBinary()</code> returns
     * <code>false</code> with the <code>SampleModel</code> of the
     * supplied <code>Raster</code> as argument.
     */
    public static void setUnpackedBinaryData(byte[] bdata,
                                             WritableRaster raster,
                                             Rectangle rect) {
        SampleModel sm = raster.getSampleModel();
        if(!isBinary(sm)) {
            throw new IllegalArgumentException("The supplied Raster does not represent a binary data set.");
        }

        int rectX = rect.x;
        int rectY = rect.y;
        int rectWidth = rect.width;
        int rectHeight = rect.height;

        DataBuffer dataBuffer = raster.getDataBuffer();

        int dx = rectX - raster.getSampleModelTranslateX();
        int dy = rectY - raster.getSampleModelTranslateY();

        MultiPixelPackedSampleModel mpp = (MultiPixelPackedSampleModel)sm;
        int lineStride = mpp.getScanlineStride();
        int eltOffset = dataBuffer.getOffset() + mpp.getOffset(dx, dy);
        int bitOffset = mpp.getBitOffset(dx);

        int k = 0;

        if(dataBuffer instanceof DataBufferByte) {
            byte[] data = ((DataBufferByte)dataBuffer).getData();
            for(int y = 0; y < rectHeight; y++) {
                int bOffset = eltOffset*8 + bitOffset;
                for(int x = 0; x < rectWidth; x++) {
                    if(bdata[k++] != (byte)0) {
                        data[bOffset/8] |=
                            (byte)(0x00000001 << (7 - bOffset & 7));
                    }
                    bOffset++;
                }
                eltOffset += lineStride;
            }
        } else if(dataBuffer instanceof DataBufferShort ||
                  dataBuffer instanceof DataBufferUShort) {
            short[] data = dataBuffer instanceof DataBufferShort ?
                ((DataBufferShort)dataBuffer).getData() :
                ((DataBufferUShort)dataBuffer).getData();
            for(int y = 0; y < rectHeight; y++) {
                int bOffset = eltOffset*16 + bitOffset;
                for(int x = 0; x < rectWidth; x++) {
                    if(bdata[k++] != (byte)0) {
                        data[bOffset/16] |=
                            (short)(0x00000001 <<
                                    (15 - bOffset % 16));
                    }
                    bOffset++;
                }
                eltOffset += lineStride;
            }
        } else if(dataBuffer instanceof DataBufferInt) {
            int[] data = ((DataBufferInt)dataBuffer).getData();
            for(int y = 0; y < rectHeight; y++) {
                int bOffset = eltOffset*32 + bitOffset;
                for(int x = 0; x < rectWidth; x++) {
                    if(bdata[k++] != (byte)0) {
                        data[bOffset/32] |=
                            (int)(0x00000001 <<
                                  (31 - bOffset % 32));
                    }
                    bOffset++;
                }
                eltOffset += lineStride;
            }
        }
    }

    public static boolean isBinary(SampleModel sm) {
        return sm instanceof MultiPixelPackedSampleModel &&
            ((MultiPixelPackedSampleModel)sm).getPixelBitStride() == 1 &&
            sm.getNumBands() == 1;
    }

    public static ColorModel createColorModel(ColorSpace colorSpace,
                                              SampleModel sampleModel) {
        ColorModel colorModel = null;

        if(sampleModel == null) {
            throw new IllegalArgumentException("The provided sample model is null.");
        }

        int numBands = sampleModel.getNumBands();
        if (numBands < 1 || numBands > 4) {
            return null;
        }

        int dataType = sampleModel.getDataType();
        if (sampleModel instanceof ComponentSampleModel) {
            if (dataType < DataBuffer.TYPE_BYTE ||
                //dataType == DataBuffer.TYPE_SHORT ||
                dataType > DataBuffer.TYPE_DOUBLE) {
                return null;
            }

            if (colorSpace == null)
                colorSpace =
                    numBands <= 2 ?
                    ColorSpace.getInstance(ColorSpace.CS_GRAY) :
                    ColorSpace.getInstance(ColorSpace.CS_sRGB);

            boolean useAlpha = (numBands == 2) || (numBands == 4);
            int transparency = useAlpha ?
                               Transparency.TRANSLUCENT : Transparency.OPAQUE;

            boolean premultiplied = false;

            int dataTypeSize = DataBuffer.getDataTypeSize(dataType);
            int[] bits = new int[numBands];
            for (int i = 0; i < numBands; i++) {
                bits[i] = dataTypeSize;
            }

            colorModel = new ComponentColorModel(colorSpace,
                                                 bits,
                                                 useAlpha,
                                                 premultiplied,
                                                 transparency,
                                                 dataType);
        } else if (sampleModel instanceof SinglePixelPackedSampleModel) {
            SinglePixelPackedSampleModel sppsm =
                (SinglePixelPackedSampleModel)sampleModel;

            int[] bitMasks = sppsm.getBitMasks();
            int rmask = 0;
            int gmask = 0;
            int bmask = 0;
            int amask = 0;

            numBands = bitMasks.length;
            if (numBands <= 2) {
                rmask = gmask = bmask = bitMasks[0];
                if (numBands == 2) {
                    amask = bitMasks[1];
                }
            } else {
                rmask = bitMasks[0];
                gmask = bitMasks[1];
                bmask = bitMasks[2];
                if (numBands == 4) {
                    amask = bitMasks[3];
                }
            }

            int[] sampleSize = sppsm.getSampleSize();
            int bits = 0;
            for (int i = 0; i < sampleSize.length; i++) {
                bits += sampleSize[i];
            }

            if (colorSpace == null)
                colorSpace = ColorSpace.getInstance(ColorSpace.CS_sRGB);

            colorModel =
                new DirectColorModel(colorSpace,
                                     bits, rmask, gmask, bmask, amask,
                                     false,
                                     sampleModel.getDataType());
        } else if (sampleModel instanceof MultiPixelPackedSampleModel) {
            int bits =
                ((MultiPixelPackedSampleModel)sampleModel).getPixelBitStride();
            int size = 1 << bits;
            byte[] comp = new byte[size];

            for (int i = 0; i < size; i++)
                comp[i] = (byte)(255 * i / (size - 1));

            colorModel = new IndexColorModel(bits, size, comp, comp, comp);
        }

        return colorModel;
    }

    /** Converts the provided object to <code>String</code> */
    public static String convertObjectToString(Object obj) {
        if (obj == null)
            return "";

        String s = "";
        if (obj instanceof byte[]) {
            byte[] bArray = (byte[])obj;
            for (int i = 0; i < bArray.length; i++)
                s += bArray[i] + " ";
            return s;
        }

        if (obj instanceof int[]) {
            int[] iArray = (int[])obj;
            for (int i = 0; i < iArray.length; i++)
                s += iArray[i] + " " ;
            return s;
        }

        if (obj instanceof short[]) {
            short[] sArray = (short[])obj;
            for (int i = 0; i < sArray.length; i++)
                s += sArray[i] + " " ;
            return s;
        }

        return obj.toString();
    }
}
