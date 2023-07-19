package io.openmessaging.benchmark.utils;

import java.util.concurrent.TimeUnit;

import org.HdrHistogram.Histogram;

/**
 * Wraps an HDR histogram and provides additional state and functionality.
 *
 * The open messaging benchmark makes heavy use of HDR histograms to track statistics,
 * and often applies the same operations to several historgrams. This class encapsulates
 * those common operations and associated state in order to provide an "augmented"
 * histogram which reduces boilerplate.
 */
public final class OmbHistogram {

    static final TimeUnit DEFAULT_TIME_UNIT = TimeUnit.MILLISECONDS;

    /**
     * See {@link org.HdrHistogram.Histogram#Histogram(int)}.
     *
     * @param numberOfSignificantValueDigits
     */
    public OmbHistogram(String name, final int numberOfSignificantValueDigits) {
        this(name, new Histogram(numberOfSignificantValueDigits), DEFAULT_TIME_UNIT);
    }

    public OmbHistogram(String name, Histogram histo, TimeUnit displayUnit) {
        this.name = name;
        this.histo = histo;
        this.displayUnit = displayUnit;
    }

    /**
     * @return the underlying HDR histogram object
     */
    public Histogram getHistogram() { return histo; }

    /**
     * Gets the name of histogram, which generally represents the underlying
     * quantity/metric the histogram is tracking.
     *
     * @return the human-readable name of this histogram
     */
    public String getName() { return name; }


    /** @return the preferred display TimeUnit */
    public TimeUnit getDisplayUnit() { return displayUnit; }

    private final String name;
    private final Histogram histo;
    private TimeUnit displayUnit;
}
