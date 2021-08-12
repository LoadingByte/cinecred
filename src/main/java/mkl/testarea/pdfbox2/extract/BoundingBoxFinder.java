package mkl.testarea.pdfbox2.extract;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.Arrays;

import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * <a href="https://stackoverflow.com/questions/52821421/how-do-determine-location-of-actual-pdf-content-with-pdfbox">
 * How do determine location of actual PDF content with PDFBox?
 * </a>
 * <p>
 * This stream engine determines the bounding box of the static content
 * of a page. Beware, it is not very sophisticated; in particular it
 * does not ignore invisible content like a white background rectangle,
 * text drawn in rendering mode "invisible", arbitrary content covered
 * by a white filled path, white parts of bitmap images, ... Furthermore,
 * it ignores clip paths.
 * </p>
 *
 * @author mklink
 */
public class BoundingBoxFinder extends PDFGraphicsStreamEngine {
    public BoundingBoxFinder(PDPage page) {
        super(page);
    }

    public Rectangle2D getBoundingBox() {
        return rectangle;
    }

    //
    // Text
    //
    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
            throws IOException {
        super.showGlyph(textRenderingMatrix, font, code, displacement);
        Shape shape = calculateGlyphBounds(textRenderingMatrix, font, code);
        if (shape != null) {
            Rectangle2D rect = shape.getBounds2D();
            add(rect);
        }
    }

    /**
     * Copy of <code>org.apache.pdfbox.examples.util.DrawPrintTextLocations.calculateGlyphBounds(Matrix, PDFont, int)</code>.
     */
    private Shape calculateGlyphBounds(Matrix textRenderingMatrix, PDFont font, int code) throws IOException
    {
        GeneralPath path = null;
        AffineTransform at = textRenderingMatrix.createAffineTransform();
        at.concatenate(font.getFontMatrix().createAffineTransform());
        if (font instanceof PDType3Font)
        {
            // It is difficult to calculate the real individual glyph bounds for type 3 fonts
            // because these are not vector fonts, the content stream could contain almost anything
            // that is found in page content streams.
            PDType3Font t3Font = (PDType3Font) font;
            PDType3CharProc charProc = t3Font.getCharProc(code);
            if (charProc != null)
            {
                BoundingBox fontBBox = t3Font.getBoundingBox();
                PDRectangle glyphBBox = charProc.getGlyphBBox();
                if (glyphBBox != null)
                {
                    // PDFBOX-3850: glyph bbox could be larger than the font bbox
                    glyphBBox.setLowerLeftX(Math.max(fontBBox.getLowerLeftX(), glyphBBox.getLowerLeftX()));
                    glyphBBox.setLowerLeftY(Math.max(fontBBox.getLowerLeftY(), glyphBBox.getLowerLeftY()));
                    glyphBBox.setUpperRightX(Math.min(fontBBox.getUpperRightX(), glyphBBox.getUpperRightX()));
                    glyphBBox.setUpperRightY(Math.min(fontBBox.getUpperRightY(), glyphBBox.getUpperRightY()));
                    path = glyphBBox.toGeneralPath();
                }
            }
        }
        else if (font instanceof PDVectorFont)
        {
            PDVectorFont vectorFont = (PDVectorFont) font;
            path = vectorFont.getPath(code);

            if (font instanceof PDTrueTypeFont)
            {
                PDTrueTypeFont ttFont = (PDTrueTypeFont) font;
                int unitsPerEm = ttFont.getTrueTypeFont().getHeader().getUnitsPerEm();
                at.scale(1000d / unitsPerEm, 1000d / unitsPerEm);
            }
            if (font instanceof PDType0Font)
            {
                PDType0Font t0font = (PDType0Font) font;
                if (t0font.getDescendantFont() instanceof PDCIDFontType2)
                {
                    int unitsPerEm = ((PDCIDFontType2) t0font.getDescendantFont()).getTrueTypeFont().getHeader().getUnitsPerEm();
                    at.scale(1000d / unitsPerEm, 1000d / unitsPerEm);
                }
            }
        }
        else if (font instanceof PDSimpleFont)
        {
            PDSimpleFont simpleFont = (PDSimpleFont) font;

            // these two lines do not always work, e.g. for the TT fonts in file 032431.pdf
            // which is why PDVectorFont is tried first.
            String name = simpleFont.getEncoding().getName(code);
            path = simpleFont.getPath(name);
        }
        else
        {
            // shouldn't happen, please open issue in JIRA
            System.out.println("Unknown font class: " + font.getClass());
        }
        if (path == null)
        {
            return null;
        }
        return at.createTransformedShape(path.getBounds2D());
    }

    //
    // Bitmaps
    //
    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                add(ctm.transformPoint(x, y));
            }
        }
    }

    //
    // Paths
    //
    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) throws IOException {
        addToPath(p0, p1, p2, p3);
    }

    @Override
    public void clip(int windingRule) throws IOException {
    }

    @Override
    public void moveTo(float x, float y) throws IOException {
        addToPath(x, y);
    }

    @Override
    public void lineTo(float x, float y) throws IOException {
        addToPath(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) throws IOException {
        addToPath(x1, y1);
        addToPath(x2, y2);
        addToPath(x3, y3);
    }

    private static final Point2D DUMMY_POINT = new Point2D.Float();

    @Override
    public Point2D getCurrentPoint() throws IOException {
        // The operators processors for lineTo, curveTo, etc. emit warnings if this method returns null.
        // We return some dummy point to silence those warnings.
        return DUMMY_POINT;
    }

    @Override
    public void closePath() throws IOException {
    }

    @Override
    public void endPath() throws IOException {
        rectanglePath = null;
    }

    @Override
    public void strokePath() throws IOException {
        addPath();
    }

    @Override
    public void fillPath(int windingRule) throws IOException {
        addPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) throws IOException {
        addPath();
    }

    @Override
    public void shadingFill(COSName shadingName) throws IOException {
    }

    void addToPath(Point2D... points) {
        Arrays.asList(points).forEach(p -> addToPath(p.getX(), p.getY()));
    }

    void addToPath(double newx, double newy) {
        if (rectanglePath == null) {
            rectanglePath = new Rectangle2D.Double(newx, newy, 0, 0);
        } else {
            rectanglePath.add(newx, newy);
        }
    }

    void addPath() {
        if (rectanglePath != null) {
            add(rectanglePath);
            rectanglePath = null;
        }
    }

    void add(Rectangle2D rect) {
        if (rectangle == null) {
            rectangle = new Rectangle2D.Double();
            rectangle.setRect(rect);
        } else {
            rectangle.add(rect);
        }
    }

    void add(Point2D... points) {
        for (Point2D point : points) {
            add(point.getX(), point.getY());
        }
    }

    void add(double newx, double newy) {
        if (rectangle == null) {
            rectangle = new Rectangle2D.Double(newx, newy, 0, 0);
        } else {
            rectangle.add(newx, newy);
        }
    }

    Rectangle2D rectanglePath = null;
    Rectangle2D rectangle = null;
}
