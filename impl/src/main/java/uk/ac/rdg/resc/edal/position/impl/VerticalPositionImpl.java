package uk.ac.rdg.resc.edal.position.impl;

import uk.ac.rdg.resc.edal.position.VerticalCrs;
import uk.ac.rdg.resc.edal.position.VerticalPosition;

/**
 * <p>
 * Implementation of {@link VerticalPosition} that is immutable provided that
 * the provided {@link VerticalCrs} is also immutable.
 * </p>
 * <p>
 * Although instances of this class are immutable, instances of subclasses may
 * not be.
 * </p>
 * 
 * @author Guy Griffiths
 */
public class VerticalPositionImpl implements VerticalPosition {

    private final Double z;
    private final VerticalCrs crs;

    /**
     * Creates a new VerticalPositionImpl with the given vertical coordinate
     * reference system and the given coordinate.
     * 
     * @param z
     *            The z coordinate (height/depth) of this position
     * @param crs
     *            The vertical coordinate reference system. If this is an
     *            immutable object then this VerticalPositionImpl will also be
     *            immutable. This may be null (in which case the CRS of this
     *            VerticalPosition must be given by some containing object).
     */
    public VerticalPositionImpl(double z, VerticalCrs crs) {
        this.z = z;
        this.crs = crs;
    }

    @Override
    public final VerticalCrs getCoordinateReferenceSystem() {
        return crs;
    }

    /** Returns the vertical coordinate of this position */
    @Override
    public final Double getZ() {
        return z;
    }

    @Override
    public int compareTo(VerticalPosition vPosition) {
        return z.compareTo(vPosition.getZ());
    }

    @Override
    public String toString() {
        return z+crs.getUnits().getUnitString();
    }
}
