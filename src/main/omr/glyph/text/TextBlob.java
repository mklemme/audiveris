//----------------------------------------------------------------------------//
//                                                                            //
//                              T e x t B l o b                               //
//                                                                            //
//----------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">                          //
//  Copyright (C) Hervé Bitteur 2000-2011. All rights reserved.               //
//  This software is released under the GNU General Public License.           //
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.   //
//----------------------------------------------------------------------------//
// </editor-fold>
package omr.glyph.text;

import omr.constant.Constant;
import omr.constant.ConstantSet;

import omr.glyph.Glyphs;
import omr.glyph.Shape;
import omr.glyph.facets.Glyph;

import omr.lag.Section;

import omr.log.Logger;

import omr.math.BasicLine;
import omr.math.Line;
import omr.math.Population;

import omr.run.Orientation;

import omr.score.common.PixelRectangle;

import omr.sheet.Scale;
import omr.sheet.SystemInfo;

import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class {@code TextBlob} handles a growing sequence of glyphs that could form
 * a single text line.
 *
 * <p>A Blob is built in two phases:<ol>
 * <li>First phase concerns only large glyphs, which are used to gradually
 * set the blob physical parameters. Small glyphs are put aside.</li>
 * <li>Second phase concerns only the small glyphs left over during first phase.
 * Each is inserted into the proper existing blob, according to compatibility
 * rules.</li></ol>
 */
public class TextBlob
{
    //~ Static fields/initializers ---------------------------------------------

    /** Specific application parameters */
    private static final Constants constants = new Constants();

    /** Usual logger utility */
    private static final Logger logger = Logger.getLogger(TextBlob.class);

    //~ Instance fields --------------------------------------------------------

    // Id for debug
    private final int         id;

    // Related system
    private final SystemInfo  system;

    // System scale
    private final Scale       scale;

    // Glyphs added so far
    private final List<Glyph> glyphs = new ArrayList<Glyph>();

    // Most important lines for a text area
    private Line           averageLine;

    // Population of glyphs tops & glyphs bottoms
    private Population     tops = new Population();
    private Population     bottoms = new Population();

    // Lastest values
    private Integer        blobTop;
    private Integer        blobBottom;

    // Global blob contour
    private PixelRectangle blobBox = null;

    // Median glyph width
    private Integer medianWidth = null;

    // Median glyph height
    private Integer medianHeight = null;

    //~ Constructors -----------------------------------------------------------

    //----------//
    // TextBlob //
    //----------//
    /**
     * Creates a new TextBlob object.
     *
     * @param id the assigned id (for debug)
     * @param system the surrounding system
     * @param initialGlyph the glyph which is used to start this blob
     */
    public TextBlob (int        id,
                     SystemInfo system,
                     Glyph      initialGlyph)
    {
        this.id = id;
        this.system = system;

        scale = system.getSheet()
                      .getScale();

        insertLargeGlyph(initialGlyph);

        if (logger.isFineEnabled()) {
            logger.fine("Created " + this);
        }
    }

    //~ Methods ----------------------------------------------------------------

    //-----------//
    // getGlyphs //
    //-----------//
    /**
     * Report the collection of glyphs that currently compose this blob
     * @return the (current) collection of glyphs
     */
    public List<Glyph> getGlyphs ()
    {
        return glyphs;
    }

    //---------------//
    // getMaxWordGap //
    //---------------//
    /**
     * Report the maximum horizontal gap between words
     * @return the maximum word abscissa gap
     */
    public int getMaxWordGap ()
    {
        return (int) Math.rint(
            getMedianHeight() * constants.maxWidthRatio.getValue());
    }

    //----------//
    // getRight //
    //----------//
    /**
     * Report the abscissa of the right side of the blob
     * @return the (current) right abscissa
     */
    public int getRight ()
    {
        return blobBox.x + blobBox.width;
    }

    //---------------------//
    // canInsertLargeGlyph //
    //---------------------//
    /**
     * Check whether the provided (large) glyph can be inserted into this blob
     * @param glyph the candidate glyph
     * @return true if test is successful
     */
    public boolean canInsertLargeGlyph (Glyph glyph)
    {
        PixelRectangle gBox = glyph.getContourBox();
        int            left = gBox.x;

        // Check abscissa gap
        int dx = left - getRight();
        int maxDx = getMaxWordGap();

        if (dx > maxDx) {
            if (logger.isFineEnabled()) {
                logger.fine(
                    "B#" + id + " Too far on right " + dx + " vs " + maxDx);
            }

            return false;
        }

        int    top = gBox.y;
        int    bot = top + gBox.height;
        double overlap = Math.min(bot, blobBottom) - Math.max(top, blobTop);
        double overlapRatio = overlap / (blobBottom - blobTop);

        if (overlapRatio < constants.minOverlapRatio.getValue()) {
            if (logger.isFineEnabled()) {
                logger.fine(
                    "B#" + id + " Too low overlapRatio " + overlapRatio +
                    " vs " + constants.minOverlapRatio.getValue());
            }

            return false;
        }

        return true;
    }

    //------------------//
    // insertLargeGlyph //
    //------------------//
    /**
     * Insert the provided glyph to the blob collection of glyphs
     * @param glyph the glyph to insertLargeGlyph
     */
    public final void insertLargeGlyph (Glyph glyph)
    {
        // Include the glyph in our blob
        glyphs.add(glyph);

        // Incrementally extend the lines
        PixelRectangle gBox = glyph.getContourBox();
        int            top = gBox.y;
        int            bottom = top + gBox.height;

        // Adjust values
        switch (glyphs.size()) {
        case 1 :
            // Start with reasonable values & Fall through
            blobBox = new PixelRectangle(gBox);

        // Fall through wanted
        case 2 :
            blobBox = blobBox.union(gBox);
            // Cumulate tops & bottoms
            tops.includeValue(top);
            bottoms.includeValue(bottom);
            blobTop = blobBox.y;
            blobBottom = blobBox.y + blobBox.height;

            break;

        default :
            blobBox = blobBox.union(gBox);
            tops.includeValue(top);
            bottoms.includeValue(bottom);
            blobTop = (int) Math.rint(tops.getMeanValue());
            blobBottom = (int) Math.rint(bottoms.getMeanValue());

            break;
        }

        //
        medianHeight = medianWidth = null;

        if (logger.isFineEnabled() && (glyphs.size() > 1)) {
            logger.fine("Added large glyph to " + this);
        }
    }

    //-------------//
    // getCompound //
    //-------------//
    /**
     * Report the allowed text glyph if any
     * @return the compound glyph if text if allowed for it, null otherwise
     */
    public Glyph getAllowedCompound ()
    {
        Glyph compound = (glyphs.size() > 1)
                         ? system.buildTransientCompound(glyphs)
                         : glyphs.iterator()
                                 .next();

        // Check that this glyph is not forbidden as text
        Glyph original = system.getSheet()
                               .getNest()
                               .getOriginal(compound);

        if (original != null) {
            compound = original;
        }

        if (compound.isShapeForbidden(Shape.TEXT)) {
            if (logger.isFineEnabled()) {
                logger.fine("Shape TEXT blacklisted");
            }

            return null;
        } else {
            return compound;
        }
    }

    //----------------//
    // getAverageLine //
    //----------------//
    public Line getAverageLine ()
    {
        if (averageLine == null) {
            averageLine = new BasicLine();

            for (Glyph glyph : glyphs) {
                for (Section section : glyph.getMembers()) {
                    averageLine.includeLine(section.getAbsoluteLine());
                }
            }
        }

        return averageLine;
    }

    //-----------//
    // getWeight //
    //-----------//
    /**
     * Report the total weight (number of pixels) for this blob
     * @return the weight of the blob
     */
    public int getWeight ()
    {
        int weight = 0;

        for (Glyph glyph : glyphs) {
            weight += glyph.getWeight();
        }

        return weight;
    }

    //----------//
    // toString //
    //----------//
    @Override
    public String toString ()
    {
        StringBuilder sb = new StringBuilder("{Blob#");
        sb.append(id);

        if (blobTop != null) {
            sb.append(" top:")
              .append(blobTop);
        }

        if (blobBottom != null) {
            sb.append(" bot:")
              .append(blobBottom);
        }

        sb.append(Glyphs.toString(" glyphs", glyphs));
        sb.append("}");

        return sb.toString();
    }

    //-----------------------//
    // tryToInsertSmallGlyph //
    //-----------------------//
    /**
     * Try to insert the provided (small) glyph into the blob (if the glyph lies
     * within the close neighborhood of the blob) and report success if any.
     * @param glyph the (small) glyph to insert
     * @return true if insertion has been done, false otherwise
     */
    public boolean tryToInsertSmallGlyph (Glyph glyph)
    {
        try {
            // Check whether the glyph is not too far from the blob
            int            smallXMargin = scale.toPixels(
                constants.smallXMargin);
            PixelRectangle fatBox = glyph.getContourBox();
            fatBox.grow(
                smallXMargin,
                (int) Math.rint(
                    getMedianHeight() * constants.smallYRatio.getValue()));

            // x check
            if (!fatBox.intersects(blobBox)) {
                return false;
            }

            Line2D.Double l2D = new Line2D.Double(
                blobBox.x,
                getAverageLine().yAtX(blobBox.x),
                blobBox.x + blobBox.width,
                getAverageLine().yAtX(blobBox.x + blobBox.width));

            if (l2D.intersects(fatBox.x, fatBox.y, fatBox.width, fatBox.height)) {
                glyphs.add(glyph);

                return true;
            }

            return false;
        } catch (Throwable ex) {
            logger.warning(
                "tryToInsertSmallGlyph error blob: " + this + " glyph#" +
                glyph.getId(),
                ex);

            return false;
        }
    }

    //-----------------//
    // getMedianHeight //
    //-----------------//
    private int getMedianHeight ()
    {
        if (medianHeight == null) {
            List<Integer> heights = new ArrayList<Integer>();

            for (Glyph glyph : glyphs) {
                heights.add(glyph.getContourBox().height);
            }

            Collections.sort(heights);
            medianHeight = heights.get(heights.size() - 1);
        }

        return medianHeight;
    }

    //----------------//
    // getMedianWidth //
    //----------------//
    private int getMedianWidth ()
    {
        if (medianWidth == null) {
            List<Integer> widths = new ArrayList<Integer>();

            for (Glyph glyph : glyphs) {
                widths.add(glyph.getContourBox().width);
            }

            Collections.sort(widths);
            medianWidth = widths.get(widths.size() - 1);
        }

        return medianWidth;
    }

    //~ Inner Classes ----------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
        extends ConstantSet
    {
        //~ Instance fields ----------------------------------------------------

        Constant.Ratio maxWidthRatio = new Constant.Ratio(
            1.0,
            "Ratio for maximum horizontal gap versus character width");
        Constant.Ratio minOverlapRatio = new Constant.Ratio(
            0.5,
            "Ratio for minimum vertical overlap beween blob and glyph");
        Scale.Fraction smallXMargin = new Scale.Fraction(
            1.0,
            "Maximum abscissa gap for small glyphs");
        Constant.Ratio smallYRatio = new Constant.Ratio(
            1.0,
            "Maximum ordinate gap for small glyphs (as ratio of mean height)");
    }
}