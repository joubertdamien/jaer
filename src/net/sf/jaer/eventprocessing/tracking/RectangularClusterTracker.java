/*
 * RectangularClusterTracker.java
 *
 * Created on December 5, 2005, 3:49 AM
 *
 * To change this template, choose Tools | Options and locate the template under
 * the Source Creation and Management node. Right-click the template and choose
 * Open. You can then make changes to the template in the Source Editor.
 */
package net.sf.jaer.eventprocessing.tracking;

import com.sun.opengl.util.GLUT;
import java.util.List;
import net.sf.jaer.aemonitor.AEConstants;
import net.sf.jaer.chip.*;
import net.sf.jaer.eventprocessing.EventFilter2D;
import net.sf.jaer.event.*;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.graphics.*;
import java.awt.*;
//import ch.unizh.ini.caviar.util.PreferencesEditor;
import java.awt.geom.*;
import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.media.opengl.GL;
import javax.media.opengl.GLAutoDrawable;
import net.sf.jaer.util.filter.LowpassFilter;

/**
 * Tracks blobs of events using a rectangular hypothesis about the object shape.
 * Many parameters constrain the hypothesese in various ways, including perspective projection, fixed aspect ratio,
 * variable size and aspect ratio, "mixing factor" that determines how much each event moves a cluster, etc.
 * <p>
 * @author tobi
 */
public class RectangularClusterTracker extends EventFilter2D implements Observer, ClusterTrackerInterface, FrameAnnotater /*, PreferenceChangeListener*/ {
    // TODO split out the Cluster object as it's own class.
    // TODO delegate worker object to update the clusters (RectangularClusterTrackerDelegate)

    public static String getDescription() {
        return "Tracks multiple moving compact (not linear) objects";
    }
    /** The list of clusters. */
    volatile protected java.util.List<Cluster> clusters = new LinkedList<Cluster>();
    private AEChipRenderer renderer;
//    /** the number of classes of objects */
//    private final int NUM_CLASSES=2;
    /** scaling can't make cluster bigger or smaller than this ratio to default cluster size. */
    public static final float MAX_SCALE_RATIO = 2;
//    private float classSizeRatio=getPrefs().getFloat("RectangularClusterTracker.classSizeRatio",2);
//    private boolean sizeClassificationEnabled=getPrefs().getBoolean("RectangularClusterTracker.sizeClassificationEnabled",true);
    private int numVisibleClusters = 0;
    /** maximum and minimum allowed dynamic aspect ratio when dynamic instantaneousAngle is disabled. */
    public static final float ASPECT_RATIO_MAX_DYNAMIC_ANGLE_DISABLED = 2.5f, ASPECT_RATIO_MIN_DYNAMIC_ANGLE_DISABLED = 0.5f;
    /** maximum and minimum allowed dynamic aspect ratio when dynamic instantaneousAngle is enabled; then min aspect ratio is set to 1 to make instantaneousAngle
     * point along an edge in the scene.
     */
    public static final float ASPECT_RATIO_MAX_DYNAMIC_ANGLE_ENABLED = 1, ASPECT_RATIO_MIN_DYNAMIC_ANGLE_ENABLED = 0.5f;
    protected float defaultClusterRadius;
    /** amount each event moves COM of cluster towards itself. */
    protected float mixingFactor = getPrefs().getFloat("RectangularClusterTracker.mixingFactor", 0.05f);
//    protected float velocityMixingFactor=getPrefs().getFloat("RectangularClusterTracker.velocityMixingFactor",0.0005f); // mixing factor for velocityPPT computation
//    private float velocityTauMs=getPrefs().getFloat("RectangularClusterTracker.velocityTauMs",10);
    private int velocityPoints = getPrefs().getInt("RectangularClusterTracker.velocityPoints", 10);
    private float surround = getPrefs().getFloat("RectangularClusterTracker.surround", 2f);
    private boolean dynamicSizeEnabled = getPrefs().getBoolean("RectangularClusterTracker.dynamicSizeEnabled", false);
    private boolean dynamicAspectRatioEnabled = getPrefs().getBoolean("RectangularClusterTracker.dynamicAspectRatioEnabled", false);
    private boolean dynamicAngleEnabled = getPrefs().getBoolean("RectangularClusterTracker.dynamicAngleEnabled", false);
    private boolean pathsEnabled = getPrefs().getBoolean("RectangularClusterTracker.pathsEnabled", true);
    private int pathLength = getPrefs().getInt("RectangularClusterTracker.pathLength", 100);
    private boolean colorClustersDifferentlyEnabled = getPrefs().getBoolean("RectangularClusterTracker.colorClustersDifferentlyEnabled", false);
    private boolean useOnePolarityOnlyEnabled = getPrefs().getBoolean("RectangularClusterTracker.useOnePolarityOnlyEnabled", false);
    private boolean useOffPolarityOnlyEnabled = getPrefs().getBoolean("RectangularClusterTracker.useOffPolarityOnlyEnabled", false);
    private float aspectRatio = getPrefs().getFloat("RectangularClusterTracker.aspectRatio", 1f);
    private float clusterSize = getPrefs().getFloat("RectangularClusterTracker.clusterSize", 0.1f);
    protected boolean growMergedSizeEnabled = getPrefs().getBoolean("RectangularClusterTracker.growMergedSizeEnabled", false);
    private boolean useVelocity = getPrefs().getBoolean("RectangularClusterTracker.useVelocity", true); // enabling this enables both computation and rendering of cluster velocities
    private boolean logDataEnabled = false;
    private boolean logClustersSeparatelyEnabled = getPrefs().getBoolean("RectangularClusterTracker.logClustersSeparatelyEnabled", false);
//    private boolean classifierEnabled = getPrefs().getBoolean("RectangularClusterTracker.classifierEnabled", false);
//    private float classifierThreshold = getPrefs().getFloat("RectangularClusterTracker.classifierThreshold", 0.2f);
    private boolean showAllClusters = getPrefs().getBoolean("RectangularClusterTracker.showAllClusters", false);
    private boolean useNearestCluster = getPrefs().getBoolean("RectangularClusterTracker.useNearestCluster", false); // use the nearest cluster to an event, not the first containing it
    private boolean clusterLifetimeIncreasesWithAge = getPrefs().getBoolean("RectangularClusterTracker.clusterLifetimeIncreasesWithAge", true);
    private float predictiveVelocityFactor = prefs().getFloat("RectangularClusterTracker.predictiveVelocityFactor",1);// making this M=10, for example, will cause cluster to substantially lead the events, then slow down, speed up, etc.
    private boolean highwayPerspectiveEnabled = getPrefs().getBoolean("RectangularClusterTracker.highwayPerspectiveEnabled", false);
    private int thresholdEventsForVisibleCluster = getPrefs().getInt("RectangularClusterTracker.thresholdEventsForVisibleCluster", 10);
    private float thresholdVelocityForVisibleCluster = getPrefs().getFloat("RectangularClusterTracker.thresholdVelocityForVisibleCluster", 0);
    private int clusterLifetimeWithoutSupportUs = getPrefs().getInt("RectangularClusterTracker.clusterLifetimeWithoutSupport", 10000);
    private boolean enableClusterExitPurging = getPrefs().getBoolean("RectangularClusterTracker.enableClusterExitPurging", true);
    private float velAngDiffDegToNotMerge = getPrefs().getFloat("RectangularClusterTracker.velAngDiffDegToNotMerge", 60);
    private boolean showClusterNumber = getPrefs().getBoolean("RectangularClusterTracker.showClusterNumber", false);
    private boolean showClusterEps = getPrefs().getBoolean("RectangularClusterTracker.showClusterEps", false);
    private boolean showClusterVelocity = getPrefs().getBoolean("RectangularClusterTracker.showClusterVelocity", false);
    private float velocityVectorScaling = getPrefs().getFloat("RectangularClusterTracker.velocityVectorScaling", 1);
    private final float VELOCITY_VECTOR_SCALING = 1e6f; // to scale rendering of cluster velocityPPT vector, velocityPPT is in pixels/tick=pixels/us so this gives 1 screen pixel per 1 pix/s actual vel
    private int loggingIntervalUs = getPrefs().getInt("RectangularClusterTracker.loggingIntervalUs", 1000);
    private int logFrameNumber = 0;
    protected ClusterLogger clusterLogger = new ClusterLogger();
    private boolean initializeVelocityToAverage = getPrefs().getBoolean("RectangularClusterTracker.initializeVelocityToAverage", false);
    private Point2D.Float averageVelocityPPT = new Point2D.Float();
    private final float averageVelocityMixingFactor = 0.001f; // average velocities of all clusters mixed with this factor to produce this "prior" on initial cluster velocityPPT
    private boolean showClusterMass = getPrefs().getBoolean("RectangularClusterTracker.showClusterMass", false);
    private boolean filterEventsEnabled = getPrefs().getBoolean("RectangularClusterTracker.filterEventsEnabled", false); // enables filtering events so that output events only belong to clustera and point to the clusters.
    private float velocityTauMs = getPrefs().getFloat("RectangularClusterTracker.velocityTauMs", 10);
    private int maxNumClusters = getPrefs().getInt("RectangularClusterTracker.maxNumClusters", 10);
    private boolean surroundInhibitionEnabled=prefs().getBoolean("RectangularClusterTracker.surroundInhibitionEnabled",false);

    public enum ClusterLoggingMethod {

        LogFrames, LogClusters
    };
    private ClusterLoggingMethod clusterLoggingMethod = ClusterLoggingMethod.valueOf(getPrefs().get("RectangularClusterTracker.clusterLoggingMethod", ClusterLoggingMethod.LogFrames.toString()));

    /**
     * Creates a new instance of RectangularClusterTracker.
     *
     *
     */
    public RectangularClusterTracker(AEChip chip) {
        super(chip);
        this.chip = chip;
        renderer = (AEChipRenderer) chip.getRenderer();
        initFilter();
        chip.addObserver(this);
        addObserver(this); // to handle updates during packet
        final String sizing = "Sizing", movement = "Movement", lifetime = "Lifetime", disp = "Display", global = PARAM_GROUP_GLOBAL, update = "Update", logging = "Logging";
        setPropertyTooltip(lifetime, "enableClusterExitPurging", "enables rapid purging of clusters that hit edge of scene");
        setPropertyTooltip(sizing, "defaultClusterRadius", "default starting size of cluster as fraction of chip size");
        setPropertyTooltip(movement, "mixingFactor", "how much cluster is moved towards an event, as a fraction of the distance from the cluster to the event");
        setPropertyTooltip(movement, "velocityPoints", "the number of recent path points (one per packet of events) to use for velocity vector regression");
        setPropertyTooltip(movement, "velocityTauMs", "lowpass filter time constant in ms for velocity updates; effectively limits acceleration");
        setPropertyTooltip(sizing, "surround", "the radius is expanded by this ratio to define events that pull radius of cluster");
        setPropertyTooltip(sizing, "dynamicSizeEnabled", "size varies dynamically depending on cluster events");
        setPropertyTooltip(sizing, "dynamicAspectRatioEnabled", "aspect ratio of cluster depends on events");
        setPropertyTooltip(sizing, "dynamicAngleEnabled", "angle of cluster depends on events, otherwise angle is zero");
        setPropertyTooltip(disp, "pathsEnabled", "draw paths of clusters over some window");
        setPropertyTooltip(disp, "pathLength", "paths are at most this many packets long");
        setPropertyTooltip(disp, "colorClustersDifferentlyEnabled", "each cluster gets assigned a random color, otherwise color indicates ages");
        setPropertyTooltip(update, "useOnePolarityOnlyEnabled", "use only one event polarity");
        setPropertyTooltip(update, "useOffPolarityOnlyEnabled", "use only OFF events, not ON - if useOnePolarityOnlyEnabled");
        setPropertyTooltip(sizing, "aspectRatio", "default (or initial) aspect ratio, <1 is wide");
        setPropertyTooltip(sizing, "clusterSize", "size (starting) in fraction of chip max size");
        setPropertyTooltip(update, "growMergedSizeEnabled", "enabling makes merged clusters take on sum of sizes, otherwise they take on size of older cluster");
        setPropertyTooltip(movement, "useVelocity", "uses measured cluster velocity to predict future position; vectors are scaled " + String.format("%.1f pix/pix/s", VELOCITY_VECTOR_SCALING / AEConstants.TICK_DEFAULT_US * 1e-6));
        setPropertyTooltip(disp, "classifierEnabled", "colors clusters based on single size metric");
        setPropertyTooltip(disp, "classifierThreshold", "the boundary for cluster size classification in fractions of chip max dimension");
        setPropertyTooltip(disp, "showAllClusters", "shows all clusters, not just those with sufficient support");
        setPropertyTooltip(movement, "useNearestCluster", "event goes to nearest cluster, not to first (usually oldest) cluster containing it");
        setPropertyTooltip(lifetime, "clusterLifetimeIncreasesWithAge", "older clusters can live longer (up to clusterLifetimeWithoutSupportUs) without support, good for objects that stop (like walking flies)");
        setPropertyTooltip(lifetime, "clusterLifetimeWithoutSupportUs", "how long a cluster lives on without any events (us)");
        setPropertyTooltip(movement, "predictiveVelocityFactor", "how much cluster position leads position based on estimated velocity");
        setPropertyTooltip(sizing, "highwayPerspectiveEnabled", "Cluster size depends on perspective location; mouse click defines horizon");
        setPropertyTooltip(lifetime, "thresholdEventsForVisibleCluster", "Cluster needs this many events to be visible");
        setPropertyTooltip(lifetime, "thresholdVelocityForVisibleCluster", "cluster must have at least this velocity in pixels/sec to become visible");
        setPropertyTooltip(global, "maxNumClusters", "Sets the maximum potential number of clusters");
        setPropertyTooltip(update, "velAngDiffDegToNotMerge", "relative angle in degrees of cluster velocity vectors to not merge overlapping clusters");
        setPropertyTooltip(disp, "showClusterVelocity", "annotates velocity in pixels/second");
        setPropertyTooltip(disp, "showClusterEps", "shows cluster events per second");
        setPropertyTooltip(disp, "showClusterNumber", "shows cluster ID number");
        setPropertyTooltip(disp, "showClusterMass", "shows cluster mass; mass is decaying measure of the rate of captured events");
        setPropertyTooltip(disp, "velocityVectorScaling", "scaling of drawn velocity vectors");
        setPropertyTooltip(logging, "logDataEnabled", "writes a cluster log matlab file called RectangularClusterTrackerLog.m in the startup folder host/java");
        setPropertyTooltip(logging, "loggingIntervalUs", "interval in us between logging cluster info to logging file");
//        setPropertyTooltip(logging, "logClustersSeparatelyEnabled", "if enabled, clusters histories are logged on cluster purging");
        setPropertyTooltip(logging, "clusterLoggingMethod", "method for logging cluster data: LogFrames logs at specified time intervals; LogClusters logs each valid cluster on its death");
        setPropertyTooltip(movement, "initializeVelocityToAverage", "initializes cluster velocity to moving average of cluster velocities; otherwise initialized to zero");
        setPropertyTooltip(global, "filterEventsEnabled", "<html>If disabled, input packet is unaltered. <p>If enabled, output packet contains RectangularClusterTrackerEvent, <br>events refer to containing cluster, and non-owned events are discarded.");
        setPropertyTooltip(lifetime,"surroundInhibitionEnabled","Enabling this option causes events in the surround region to actively reduce the cluster mass, enabling tracking of only isolated features");
//        setPropertyTooltip("sizeClassificationEnabled", "Enables coloring cluster by size threshold");
//        setPropertyTooltip("opticalGyroTauHighpassMs", "highpass filter time constant in ms for optical gyro position, increase to forget DC value more slowly");
//    {setPropertyTooltip("velocityMixingFactor","how much cluster velocityPPT estimate is updated by each packet (IIR filter constant)");}
//    {setPropertyTooltip("velocityTauMs","time constant in ms for cluster velocityPPT lowpass filter");}


    }

    /** Logs data if clusterLoggingMethod==ClusterLoggingMethod.LogFrames and loggingIntervalUs has passed since
     * last log record. The frame number is computed as <code> ev.timestamp / loggingIntervalUs</code>.
     * @param ev
     * @param ae
     */
    protected void logData(BasicEvent ev, EventPacket<BasicEvent> ae) {
        if (logDataEnabled && clusterLoggingMethod == ClusterLoggingMethod.LogFrames) {
            if ((ev.timestamp / loggingIntervalUs) >= logFrameNumber) {
                logFrameNumber = ev.timestamp / loggingIntervalUs;
                clusterLogger.logClusters(ae, logFrameNumber);
            }
        }
    }

    /** Handles logging clusters to a file for later analysis.
     * 
     */
    protected class ClusterLogger {

        String dateString = null;
        String filename = null;
        String functionName = null;
        /** The stream to write on. */
        private PrintStream logStream = null;
        private int clusterCounter = 0;
        final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd'T'HHmmss");

        /** Writes the footer, closes the file and nulls the stream.
         *
         */
        protected void close() {
            if (logStream != null) {
                writeFooter();
                logStream.flush();
                logStream.close();
                logStream = null;
                log.info("Closed log data file " + filename);
            }
        }

        /** Opens the file and prints the header to it.
         * The file is written to the startup folder.
         *
         */
        protected void open() {
            try {
                clusterCounter = 0;
                dateString = DATE_FORMAT.format(new Date());
                functionName = "RectangularClusterTrackerLog_" + dateString;
                filename = functionName + ".m";
                log.info("created cluster logging file at " + filename);
                logStream = new PrintStream(new BufferedOutputStream(new FileOutputStream(new File(filename))));
                writeHeader();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /** Logs the clusters at frame number as a matlab switch statement.
        @param ae the event packet.
         * @param logNumber the case # (analagous to frame) of this log record.
         */
        protected void logClusters(EventPacket<BasicEvent> ae, int logNumber) {
            if (!isLogDataEnabled() || clusterLoggingMethod != clusterLoggingMethod.LogFrames) {
                return;
            }

            {
                if (logStream != null) {
                    logStream.println(String.format("case %d", logNumber));
                    logStream.println(String.format("particles=["));
                    for (Cluster c : clusters) {
                        if (!c.isVisible()) {
                            continue;
                        }
                        writeCluster(c);
//                    logStream.println(String.format("%d %d %f %f %f",c.getClusterNumber(),c.lastEventTimestamp,c.location.x,c.location.y,c.averageEventDistance));
                        if (logStream.checkError()) {
                            log.warning("eroror logging data");
                        }
                    }
                    logStream.println("];");
                } else {
                }
            }
        }

        /** Writes out a single cluster info for the case statement.
        @param c the cluster.
         */
        protected void writeCluster(Cluster c) {
            logStream.println(String.format("%d %e %e %e %e", c.lastEventTimestamp, c.location.x, c.location.y, c.velocityPPS.x, c.velocityPPS.y));
        }

        /** Writes header.
         *
         */
        protected void writeHeader() {
            if (logStream == null) {
                return;
            }
            switch (clusterLoggingMethod) {
                case LogFrames:
                    logStream.println("function [particles]=" + functionName + "(frameN)");
                    logStream.println("% written " + new Date());
                    logStream.println("% each case is one 'frame' and the case switch number is the frame number. Frame intervals are loggingIntervalUs.");
                    logStream.println("% loggingIntervalUs = " + loggingIntervalUs);
                    logStream.println("% fields for each particle are ");
                    logStream.println("% " + getFieldDescription());
                    logStream.println("switch (frameN)");
                    break;
                case LogClusters:
                    logStream.println("function [path]=" + functionName + "(clusterNumber)");
                    logStream.println("% written " + new Date());
                    logStream.println("% each case is one 'cluster' history and the case switch number is the cluster number. ");
                    logStream.println("% loggingIntervalUs = " + loggingIntervalUs + "; % not relevant here since each cluster is logged on its death");
                    logStream.println("% fields for each particle are ");
                    logStream.println("% " + getFieldDescription());
                    logStream.println("switch (clusterNumber)");
                    break;
            }
        }

        /** Returns the description of the fields that are logged for each cluster, e.g. "lasttimestampus x y xvel yvel".
         * 
         * @return the description. Matlab comment character is prepended. 
         */
        protected String getFieldDescription() {
            switch (clusterLoggingMethod) {
                case LogFrames:
                    return "particles: lasttimestampus x y xvel yvel";
                case LogClusters:
                    return "path: timestampus, x, y, nevents";
                default:
                    return "unknown logging method";
            }
        }

        /** Writes footer.
         *
         */
        protected void writeFooter() {
            if (logStream == null) {
                return;
            }
            logStream.println("otherwise");
            switch (clusterLoggingMethod) {
                case LogFrames:
                    logStream.println("particles=[];");
                    break;
                case LogClusters:
                    logStream.println("clusters=[];");
            }
            //logStream.println("cla;");
            //logStream.println("set(gca,'xlim',xlim,'ylim',ylim)");
            logStream.println("end; %switch");
        }

        /**
         * @return the logStream
         */
        public PrintStream getLogStream() {
            return logStream;
        }

        private void logClusterHistories(java.util.List<Cluster> pruneList) {
            for (Cluster c : pruneList) {
                // log valid clusters to logging data file
                if (isLoggable(c)) {
                    logClusterHistory(c);
                }
            }
        }

        /** logs the cluster history to the logging using the clusterLogger */
        private void logClusterHistory(Cluster c) {
            PrintStream s = clusterLogger.getLogStream();
            if (s == null) {
                return;
            }
//            s.println("case " + c.getClusterNumber());
            s.println("case " + clusterCounter++);
            s.print("path=[");
            List<ClusterPathPoint> path = c.getPath();
            for (ClusterPathPoint p : path) {
                s.print(p + ";");
            }
            s.println("];");
        }

        private boolean isLoggable(Cluster c) {
            return c.isWasEverVisible();
        }
    }

    /**
     * merge clusters that are too close to each other and that have sufficiently similar velocities (if velocityRatioToNotMergeClusters).
    this must be done interatively, because merging 4 or more clusters feedforward can result in more clusters than
    you start with. each time we merge two clusters, we start over, until there are no more merges on iteration.
    for each cluster, if it is close to another cluster then merge them and start over.
     */
    private void mergeClusters() {
        boolean mergePending;
        Cluster c1 = null;
        Cluster c2 = null;
        do {
            mergePending = false;
            int nc = clusters.size();
            outer:
            for (int i = 0; i < nc; i++) {
                c1 = clusters.get(i);
                for (int j = i + 1; j < nc; j++) {
                    c2 = clusters.get(j); // get the other cluster
                    final boolean overlapping = c1.distanceTo(c2) < (c1.getRadius() + c2.getRadius());
                    boolean velSimilar = true; // start assuming velocities are similar
                    if (overlapping && velAngDiffDegToNotMerge > 0 && c1.isVisible() && c2.isVisible() && c1.isVelocityValid() && c2.isVelocityValid() && c1.velocityAngleToRad(c2) > velAngDiffDegToNotMerge * Math.PI / 180) {
                        // if velocities valid for both and velocities are sufficiently different
                        velSimilar = false; // then flag them as different velocities
                    }
                    if (overlapping && velSimilar) {
                        // if cluster is close to another cluster, merge them
                        // if distance is less than sum of radii merge them and if velAngle < threshold
                        mergePending = true;
                        break outer; // break out of the outer loop
                    }
                }
            }
            if (mergePending && c1 != null && c2 != null) {
                pruneList.add(c1);
                pruneList.add(c2);
                clusters.remove(c1);
                clusters.remove(c2);
                clusters.add(new Cluster(c1, c2));
//                    System.out.println("merged "+c1+" and "+c2);
            }
        } while (mergePending);
        // update all cluster sizes
        // note that without this following call, clusters maintain their starting size until they are merged with another cluster.
        if (isHighwayPerspectiveEnabled()) {
            for (Cluster c : clusters) {
                c.setRadius(defaultClusterRadius);
            }
        }
    }

    /** Prunes out old clusters that don't have support or that should be purged for some other reason.
     */
    private void pruneClusters(int t) {
        pruneList.clear();
        for (Cluster c : clusters) {
            int t0 = c.lastEventTimestamp;
//            int t1=ae.getLastTimestamp();
            int timeSinceSupport = t - t0;
            if (timeSinceSupport == 0) {
                continue; // don't kill off cluster spawned from first event
            }
            boolean massTooSmall = false;
//            if ( clusterLifetimeIncreasesWithAge ){
//                int age = c.getLifetime();
//                int supportTime = clusterLifetimeWithoutSupportUs;
//                if ( age < clusterLifetimeWithoutSupportUs ){
//                    supportTime = age;
//                }
//                if ( timeSinceSupport > supportTime ){
//                    killOff = true;
////                    System.out.println("pruning unsupported "+c);
//                }
//            } else{
//                if ( timeSinceSupport > clusterLifetimeWithoutSupportUs ){
//                    killOff = true;
////                    System.out.println("pruning unzupported "+c);
//                }
//            }
            if (c.getMassNow(t) < thresholdEventsForVisibleCluster) {
                massTooSmall = true;
            }
            boolean hitEdge = c.hasHitEdge();
            if (t0 > t || massTooSmall || timeSinceSupport < 0 || hitEdge) {
                // ordinarily, we discard the cluster if it hasn't gotten any support for a while, but we also discard it if there
                // is something funny about the timestamps
                pruneList.add(c);
            }
//            if(t0>t1){
//                log.warning("last cluster timestamp is later than last packet timestamp");
//            }
        }
        if (logDataEnabled && clusterLogger != null && clusterLoggingMethod == ClusterLoggingMethod.LogClusters && !pruneList.isEmpty()) {
            clusterLogger.logClusterHistories(pruneList);
        }
        clusters.removeAll(pruneList);
    }

    /** This method updates the list of clusters, pruning and
     * merging clusters and updating positions based on cluster velocities.
     * It also updates the optical gyro if enabled.
     *
     * @param ae the event packet.
     * @param t the global timestamp of the update.
     */
    private void updateClusterList(EventPacket<BasicEvent> ae, int t) {
        pruneClusters(t);
        mergeClusters();
        updateClusterLocations(t);
        updateClusterPaths(t);
    }

    /**
     * @return the enableClusterExitPurging
     */
    public boolean isEnableClusterExitPurging() {
        return enableClusterExitPurging;
    }

    /**
    Enables rapid purging of clusters that hit the edge of the scene.

     * @param enableClusterExitPurging the enableClusterExitPurging to set
     */
    public void setEnableClusterExitPurging(boolean enableClusterExitPurging) {
        this.enableClusterExitPurging = enableClusterExitPurging;
        getPrefs().putBoolean("RectangularClusterTracker.enableClusterExitPurging", enableClusterExitPurging);
    }

//    private float opticalGyroTauHighpassMs=getPrefs().getInt("RectangularClusterTracker.opticalGyroTauHighpassMs", 10000);
//   {
//    }
//    private class OpticalGyroFilters{
//        LowpassFilter x=new LowpassFilter();
//        LowpassFilter y=new LowpassFilter();
//
//        private OpticalGyroFilters(){
//            x.setTauMs(opticalGyroTauLowpassMs);
//            y.setTauMs(opticalGyroTauLowpassMs);
//        }
////        private void setTauMsHigh(float opticalGyroTauHighpassMs) {
//////            x.setTauMsHigh(opticalGyroTauHighpassMs);
//////            y.setTauMsHigh(opticalGyroTauHighpassMs);
////        }
//
//        private void setTauMsLow(float opticalGyroTauLowpassMs){
//            x.setTauMs(opticalGyroTauLowpassMs);
//            y.setTauMs(opticalGyroTauLowpassMs);
//        }
//    }
    public void initFilter() {
        initDefaults();
        defaultClusterRadius = (int) Math.max(chip.getSizeX(), chip.getSizeY()) * getClusterSize();
    }

    private void initDefaults() {
        initDefault("RectangularClusterTracker.clusterLifetimeWithoutSupport", "10000");
        initDefault("RectangularClusterTracker.maxNumClusters", "10");
        initDefault("RectangularClusterTracker.clusterSize", "0.15f");
        initDefault("RectangularClusterTracker.numEventsStoredInCluster", "100");
        initDefault("RectangularClusterTracker.thresholdEventsForVisibleCluster", "30");

//        initDefault("RectangularClusterTracker.","");
    }

    private void initDefault(String key, String value) {
        if (getPrefs().get(key, null) == null) {
            getPrefs().put(key, value);
        }
    }
//    ArrayList<Cluster> pruneList=new ArrayList<Cluster>(1);
    protected LinkedList<Cluster> pruneList = new LinkedList<Cluster>();

    /** Encapulates the nearest Cluster and the distance to it
     * 
     */
    private class ClusterAndDistance{

        public ClusterAndDistance (Cluster c,float distance){
            this.c = c;
            this.distance = distance;
        }

        Cluster c; float distance;
    }

    /** The method that actually does the tracking.
     *
     * @param in the event packet.
     * @return a possibly filtered event packet passing only events contained in the tracked and visible Clusters, depending on filterEventsEnabled.
     */
    synchronized private EventPacket<? extends BasicEvent> track(EventPacket<BasicEvent> in) {
        boolean updatedClusterList = false;
        OutputEventIterator outItr = null;
        if (filterEventsEnabled) {
            outItr = out.outputIterator(); // reset output packet
        }
        int n = in.getSize();
        if (n == 0) {
            return out;
        }

        // record cluster locations before packet is processed
        for (Cluster c : clusters) {
            c.getLastPacketLocation().setLocation(c.location);
        }

        // for each event, see which cluster it is closest to and add it to this cluster.
        // if its too far from any cluster, make a new cluster if we can
        for (BasicEvent ev : in) {
//            EventXYType ev=ae.getEvent2D(i);
            Cluster closest = null;
            if (useNearestCluster) {
                closest = getNearestCluster(ev);
            } else {
                closest = getFirstContainingCluster(ev); // find cluster that event falls within (or also within surround if scaling enabled)
            }
            if (closest != null) {
                if (filterEventsEnabled) {
                    closest.addEvent(ev, outItr);
                } else {
                    closest.addEvent(ev);
                }
            } else if (clusters.size() < maxNumClusters) { // start a new cluster
                Cluster newCluster = null;
                if (filterEventsEnabled) {
                    newCluster = new Cluster(ev, outItr);
                } else {
                    newCluster = new Cluster(ev);
                }
                clusters.add(newCluster);
            }

            updatedClusterList = maybeCallUpdateObservers(in, ev.timestamp); // callback to update()
//            if (!updateTimeInitialized) {
//                nextUpdateTimeUs = (int) (ev.timestamp + updateIntervalMs * 1000 / AEConstants.TICK_DEFAULT_US);
//                updateTimeInitialized = true;
//            }
//            // ensure cluster list is scanned at least every updateIntervalMs
//            if (ev.timestamp >= nextUpdateTimeUs) {
//                nextUpdateTimeUs = (int) (ev.timestamp + updateIntervalMs * 1000 / AEConstants.TICK_DEFAULT_US);
//                updateClusterList(in, ev.timestamp);
//                updatedClusterList = true;
//            }
            if (logDataEnabled) {
                logData(ev, in);
            }
        }
        // TODO update here again, relying on the fact that lastEventTimestamp was set by possible previous update according to
        // schedule; we have have double update of velocityPPT using same dt otherwise
        if (!updatedClusterList) {
            updateClusterList(in, in.getLastTimestamp()); // at laest once per packet update list
        }
//        for (Cluster c : clusters) {
//            if (!c.isVisible()) {
//                continue;
//            }
//            RectangularClusterTrackerEvent oe = (RectangularClusterTrackerEvent) outItr.nextOutput();
//            oe.setX((short) c.getLocation().x);
//            oe.setY((short) c.getLocation().y);
//            oe.setCluster(c);
//        }
        if (filterEventsEnabled) {
            return out;
        } else {
            return in;
        }
    }

    /** Returns total number of clusters, including those that have been
     * seeded but may not have received sufficient support yet.
     * @return number of Cluster's in clusters list.
     */
    public int getNumClusters() {
        return clusters.size();
    }

    /** Returns number of "visible" clusters; those that have received sufficient support.
     * 
     * @return number
     */
    synchronized public int getNumVisibleClusters() {
        return numVisibleClusters;
    }

    @Override
    public String toString() {
        String s = clusters != null ? Integer.toString(clusters.size()) : null;
        String s2 = "RectangularClusterTracker#" + hashCode() + " with " + s + " clusters ";
        return s2;
    }

    /**
     * Method that given event, returns closest cluster and distance to it.
    The actual computation returns the first cluster that is within the
     * minDistance of the event, which reduces the computation at the cost of reduced precision,
    unless the option useNearestCluster is enabled.
    Then the closest cluster is used, rather than the first in the list. The first cluster to be in range is usually the older
    one so usually useNearestCluster is not very beneficial.
    <p>
    The range for an event being in the cluster is defined by the cluster radius.
    If dynamicSizeEnabled is true, then the radius is multiplied by the surround.
    <p> The cluster radius is actually defined for x and y directions since the cluster may not have a square
    aspect ratio.

     * @param event the event
     * @return closest cluster object (a cluster with a distance -
    that distance is the distance between the given event and the returned cluster).
     */
    private Cluster getNearestCluster(BasicEvent event) {
        float minDistance = Float.MAX_VALUE;
        Cluster closest = null;
        float currentDistance = 0;
        for (Cluster c : clusters) {
            float rX = c.radiusX;
            float rY = c.radiusY; // this is surround region for purposes of dynamicSize scaling of cluster size or aspect ratio
            if (dynamicSizeEnabled) {
                rX *= surround;
                rY *= surround; // the event is captured even when it is in "invisible surround"
            }
            float dx, dy;
            if ((dx = c.distanceToX(event)) < rX && (dy = c.distanceToY(event)) < rY) { // needs instantaneousAngle metric
                currentDistance = dx + dy;
                if (currentDistance < minDistance) {
                    closest = c;
                    minDistance = currentDistance;
                    c.distanceToLastEvent = minDistance;
                    c.xDistanceToLastEvent = dx;
                    c.yDistanceToLastEvent = dy;
                }
            }
        }
        return closest;
    }

    /** Given AE, returns first (thus oldest) cluster that event is within.
     * The radius of the cluster here depends on whether {@link #setdynamicSizeEnabled scaling} is enabled.
     * @param event the event
     * @return cluster that contains event within the cluster's radius, modfied by aspect ratio. null is returned if no cluster is close enough.
     */
    private Cluster getFirstContainingCluster(BasicEvent event) {
        float minDistance = Float.MAX_VALUE;
        Cluster closest = null;
        float currentDistance = 0;
        for (Cluster c : clusters) {
            float rX = c.radiusX;
            float rY = c.radiusY; // this is surround region for purposes of dynamicSize scaling of cluster size or aspect ratio
            if (dynamicSizeEnabled) {
                rX *= surround;
                rY *= surround; // the event is captured even when it is in "invisible surround"
            }
            float dx, dy;
            if ((dx = c.distanceToX(event)) < rX && (dy = c.distanceToY(event)) < rY) {  // TODO needs to account for instantaneousAngle
                currentDistance = dx + dy;
                closest = c;
                minDistance = currentDistance;
                c.distanceToLastEvent = minDistance;
                c.xDistanceToLastEvent = dx;
                c.yDistanceToLastEvent = dy;

                break;
            }
        }
        return closest;
    }
    protected int clusterCounter = 0; // keeps track of absolute cluster number

    /** Updates cluster locations based on cluster velocities, if {@link #useVelocity} is enabled.
     *
     * @param t the global timestamp of the update.
     */
    private void updateClusterLocations(int t) {
        if (!useVelocity) {
            return;
        }
        for (Cluster c : clusters) {
            if (c.isVelocityValid()) {
                int dt = t - c.lastUpdateTime;
                if (dt <= 0) {
                    continue; // bogus timestamp or doesn't need update
                }
                c.location.x += c.velocityPPT.x * dt*predictiveVelocityFactor;
                c.location.y += c.velocityPPT.y * dt*predictiveVelocityFactor;
                if (initializeVelocityToAverage) {
                    // update average velocity metric for construction of new Clusters
                    averageVelocityPPT.x = (1 - averageVelocityMixingFactor) * averageVelocityPPT.x + averageVelocityMixingFactor * c.velocityPPT.x;
                    averageVelocityPPT.y = (1 - averageVelocityMixingFactor) * averageVelocityPPT.y + averageVelocityMixingFactor * c.velocityPPT.y;
                }
                c.lastUpdateTime = t;
            }
        }
    }

    /** Updates cluster path lists and counts number of visible clusters.
     *
     * @param t the update timestamp
     */
    private void updateClusterPaths(int t) {
        // update paths of clusters
        numVisibleClusters = 0;
        for (Cluster c : clusters) {
            c.updatePath(t);
            if (c.isVisible()) {
                numVisibleClusters++;
            }
        }
    }

    /** Returns list of clusters, visible and not.
     *
     * @return list of clusters.
     */
//    /**
//     * @return the opticalGyroTauHighpassMs
//     */
//    public float getOpticalGyroTauHighpassMs() {
//        return opticalGyroTauHighpassMs;
//    }
//
//    /**
//     * @param opticalGyroTauHighpassMs the opticalGyroTauHighpassMs to set
//     */
//    public void setOpticalGyroTauHighpassMs(float opticalGyroTauHighpassMs) {
//        this.opticalGyroTauHighpassMs=opticalGyroTauHighpassMs;
//        getPrefs().putFloat("RectangularClusterTracker.opticalGyroTauHighpassMs",opticalGyroTauHighpassMs);
//        opticalGyroFilters.setTauMsHigh(opticalGyroTauHighpassMs);
//    }


    /** The basic object that is tracked, which is a rectangular cluster with (optionally) variable size, aspect ratio, and angle.
     * 
     */
    public class Cluster implements ClusterInterface {

        private final int MIN_DT_FOR_VELOCITY_UPDATE = 10;
        /** location of cluster in pixels */
        public Point2D.Float location = new Point2D.Float(); // location in chip pixels
        private Point2D.Float birthLocation = new Point2D.Float(); // birth location of cluster
        private Point2D.Float lastPacketLocation = new Point2D.Float(); // location at end of last packet, used for movement sample
        /** velocityPPT of cluster in pixels/tick, where tick is timestamp tick (usually microseconds) */
        protected Point2D.Float velocityPPT = new Point2D.Float(); // velocityPPT in chip pixels/tick
        private Point2D.Float velocityPPS = new Point2D.Float(); // cluster velocityPPT in pixels/second
        private boolean velocityValid = false; // used to flag invalid or uncomputable velocityPPT
        private LowpassFilter vxFilter = new LowpassFilter(), vyFilter = new LowpassFilter();
        final float VELPPS_SCALING = 1e6f / AEConstants.TICK_DEFAULT_US;
//        public float tauMsVelocity=50; // LP filter time constant for velocityPPT change
//        private LowpassFilter velocityFilter=new LowpassFilter();
        private float radius; // in chip chip pixels
//        private float mass; // a cluster has a mass correspoding to its support - the higher the mass, the harder it is to change its velocityPPT
        private float aspectRatio, radiusX, radiusY;
        /** Angle of cluster in radians with zero being horizontal and CCW > 0. sinAngle and cosAngle are updated when instantaneousAngle is updated. */
        private float angle = 0, cosAngle = 1, sinAngle = 0;
        protected LinkedList<ClusterPathPoint> path = new LinkedList<ClusterPathPoint>();
//         protected LinkedList<ClusterPathPoint> path = new ArrayList<ClusterPathPoint>(getPathLength());
        int hitEdgeTime = 0;

        /** Computes and returns {@link #mass} at time t, using the last time an event hit this cluster
         * and
         * the {@link #clusterLifetimeWithoutSupportUs}. Does not change the mass.
         * 
         * @param t timestamp now.
         * @return the mass.
         */
        protected float getMassNow(int t) {
            float m = mass * (float) Math.exp(((float) (lastEventTimestamp - t)) / clusterLifetimeWithoutSupportUs);
            return m;
        }

        /**
         * The "mass" of the cluster is the weighted number of events it has collected.
         * The mass decays over time and is incremented by one by each collected event.
         * The mass decays with a first order time constant of clusterLifetimeWithoutSupportUs in us.
         * @return the mass
         */
        public float getMass() {
            return mass;
        }

        /**Increments mass of cluster by one after decaying it away since the {@link #lastEventTimestamp} according
        to exponential decay with time constant {@link #clusterLifetimeWithoutSupportUs}.
        @param event used for event timestamp.
         */
        protected void updateMass (BasicEvent event){
            if ( surroundInhibitionEnabled ){
                // if the event is in the surround, we decrement the mass, if inside cluster, we increment
                float normDistance=distanceToLastEvent/radius;
                float dmass=normDistance<=1?1:-1;
                 mass = dmass + mass * (float)Math.exp((float)( lastEventTimestamp - event.timestamp ) / clusterLifetimeWithoutSupportUs);
           } else{
                // don't worry about distance, just increment
                mass = 1 + mass * (float)Math.exp(( (float)lastEventTimestamp - event.timestamp ) / clusterLifetimeWithoutSupportUs);
            }
        }

        /** Returns true if the cluster center is outside the array or if this test is enabled and if the
        cluster has hit the edge of the array and has been there at least the
        minimum time for support.
        @return true if cluster has hit edge for long
        enough (getClusterLifetimeWithoutSupportUs) and test enableClusterExitPurging
         */
        private boolean hasHitEdge() {
            if (!enableClusterExitPurging) {
                return false;
            }
            int lx = (int) location.x, ly = (int) location.y;
            int sx = chip.getSizeX(), sy = chip.getSizeY();

            if (lx < 0 || lx > sx || ly < 0 || ly > sy) {
                return true;  // always prune if cluster center is outside array, e.g. from velocityPPT prediction
            }
            if (lx < radiusX || lx > sx - radiusX || ly < radiusY || ly > sy - radiusY) {
                if (hitEdgeTime == 0) {
                    hitEdgeTime = lastEventTimestamp;
                    return false;
                } else {
                    if (lastEventTimestamp - hitEdgeTime > 0/* getClusterLifetimeWithoutSupportUs()*/) {
                        return true;
                    } else {
                        return false;
                    }
                }
            }
            return false;
        }

        /** Returns true if cluster is overlapping chip border on any side.
         *
         * @return true if overlapping border.
         */
        public boolean isOverlappingBorder(){
            int lx = (int) location.x, ly = (int) location.y;
            int sx = chip.getSizeX(), sy = chip.getSizeY();

            if (lx < 0 || lx > sx || ly < 0 || ly > sy) {
                return true;  // always true if cluster is outside array, e.g. from velocityPPT prediction
            }
            if (lx < radiusX || lx > sx - radiusX || ly < radiusY || ly > sy - radiusY) {
                        return true;
            }
            return false;
        }

        /** Total number of events collected by this cluster.
         * @return the numEvents
         */
        public int getNumEvents() {
            return numEvents;
        }

        /** Sets count of events.
         * 
         * @param numEvents the numEvents to set
         */
        public void setNumEvents(int numEvents) {
            this.numEvents = numEvents;
        }

        /**
         * Cluster velocityPPT in pixels/timestamp tick as a vector. Velocity values are set during cluster upate.
         *
         * @return the velocityPPT in pixels per timestamp tick.
         * @see #getVelocityPPS()
         */
        public Point2D.Float getVelocityPPT() {
            return velocityPPT;
        }

        /**
         * The location of the cluster at the end of the previous packet.
         * Can be used to measure movement of cluster during this
         * packet.
         * @return the lastPacketLocation.
         */
        public Point2D.Float getLastPacketLocation() {
            return lastPacketLocation;
        }

        /** Updates cluster and generates new output event pointing to the cluster.
         *
         * @param ev the event.
         * @param outItr the output iterator; used to generate new output event pointing to cluster.
         */
        private void addEvent(BasicEvent ev, OutputEventIterator outItr) {
            addEvent(ev);
            if (!isVisible()) {
                return;
            }
            RectangularClusterTrackerEvent oe = (RectangularClusterTrackerEvent) outItr.nextOutput();
            oe.copyFrom(ev);
//            oe.setX((short) getLocation().x);
//            oe.setY((short) getLocation().y);
            oe.setCluster(this);
        }
//        private RollingVelocityFitter velocityFitter = new RollingVelocityFitter(path, velocityPoints);
        /** Rendered color of cluster. */
        protected Color color = null;
//        ArrayList<EventXYType> events=new ArrayList<EventXYType>();
        /** Number of events collected by this cluster.*/
        protected int numEvents = 0;
        /** Num events from previous update of cluster list. */
        protected int previousNumEvents = 0; // total number of events and number at previous packet
        /** First and last timestamp of cluster. <code>firstEventTimestamp</code> is updated when cluster becomes visible.
         * <code>lastEventTimestamp</code> is the last time the cluster was touched either by an event or by
         * some other timestamped update, e.g. {@link #updateClusterList(net.sf.jaer.event.EventPacket, int) }.
         * @see #isVisible()
         */
        protected int lastEventTimestamp, firstEventTimestamp;
        /** The "mass" of the cluster is the weighted number of events it has collected.
         * The mass decays over time and is incremented by one by each collected event.
         * The mass decays with a first order time constant of clusterLifetimeWithoutSupportUs in us.
         * If surroundInhibitionEnabled=true, then the mass is decremented by events captured in the surround.
         */
        private float mass = 1;
        /** This is the last time in timestamp ticks that the cluster was updated, either by an event
         * or by a regular update such as {@link #updateClusterLocations(int)}. This time can be used to
         * compute postion updates given a cluster velocityPPT and time now.
         */
        protected int lastUpdateTime;
        /** events/tick event rate for last two events. */
        protected float instantaneousEventRate; // in events/tick
        /** Average event rate as computed using mixingFactor.
         * @see #mixingFactor
         */
        private float avgEventRate = 0;
        protected float instantaneousISI; // ticks/event
        private float avgISI;
        /** assigned to be the absolute number of the cluster that has been created. */
        private int clusterNumber;
        /** average (mixed using mixingFactor) distance of events from cluster center, a measure of actual cluster size. */
        private float averageEventDistance, averageEventXDistance, averageEventYDistance;
        protected float distanceToLastEvent = Float.POSITIVE_INFINITY;
        protected float xDistanceToLastEvent = Float.POSITIVE_INFINITY, yDistanceToLastEvent = Float.POSITIVE_INFINITY;
        /** Flag which is set true (forever) once a cluster has first obtained sufficient support. */
        protected boolean hasObtainedSupport = false;
        private float[] rgb = new float[4];

        /** Constructs a default cluster. */
        public Cluster() {
            setRadius(defaultClusterRadius);
            float hue = random.nextFloat();
            Color c = Color.getHSBColor(hue, 1f, 1f);
            setColor(c);
            setClusterNumber(clusterCounter++);
            setAspectRatio(RectangularClusterTracker.this.getAspectRatio());
            vxFilter.setTauMs(velocityTauMs);
            vyFilter.setTauMs(velocityTauMs);
            if (initializeVelocityToAverage) {
                velocityPPT.x = averageVelocityPPT.x;
                velocityPPT.y = averageVelocityPPT.y;
                velocityValid = true;
            }
        }

        /** Overrides default hashCode to return {@link #clusterNumber}. This overriding
         * allows for storing clusters in lists and checking for them by their clusterNumber.
         *
         * @return clusterNumber
         */
        @Override
        public int hashCode() {
            return clusterNumber;
        }

        /** Two clusters are equal if their {@link #clusterNumber}'s are equal.
         *
         * @param obj another Cluster.
         * @return true if equal.
         */
        @Override
        public boolean equals(Object obj) { // derived from http://www.geocities.com/technofundo/tech/java/equalhash.html
            if (this == obj) {
                return true;
            }
            if ((obj == null) || (obj.getClass() != this.getClass())) {
                return false;
            }
            // object must be Test at this point
            Cluster test = (Cluster) obj;
            return clusterNumber == test.clusterNumber;
        }

        /** Constructs a cluster at the location of an event.
         * The numEvents, location, birthLocation, first and last timestamps are set.
         * The radius is set to defaultClusterRadius.
         *
         * @param ev the event.
         */
        public Cluster(BasicEvent ev) {
            this();
            location.x = ev.x;
            location.y = ev.y;
            birthLocation.x = ev.x;
            birthLocation.y = ev.y;
            lastPacketLocation.x = ev.x;
            lastPacketLocation.y = ev.y;
            lastEventTimestamp = ev.timestamp;
            firstEventTimestamp = lastEventTimestamp;
            lastUpdateTime = ev.timestamp;
            numEvents = 1;
            setRadius(defaultClusterRadius);
        }

        /** Draws this cluster using OpenGL.
         *
         * @param drawable area to draw this.
         */
        public void draw(GLAutoDrawable drawable) {
            final float BOX_LINE_WIDTH = 2f; // in chip
            final float PATH_POINT_SIZE = 4f;
            final float VEL_LINE_WIDTH = 4f;
            GL gl = drawable.getGL();
            int x = (int) getLocation().x;
            int y = (int) getLocation().y;


            int sy = (int) radiusY; // sx sy are (half) size of rectangle
            int sx = (int) radiusX;

            // set color and line width of cluster annotation
            setColorAutomatically();
            getColor().getRGBComponents(rgb);
            if (isVisible()) {
                gl.glColor3fv(rgb, 0);
                gl.glLineWidth(BOX_LINE_WIDTH);
            } else {
                gl.glColor3f(.3f, .3f, .3f);
                gl.glLineWidth(.5f);
            }

            // draw cluster rectangle
            drawBox(gl, x, y, sx, sy, getAngle());

            gl.glPointSize(PATH_POINT_SIZE);
            gl.glBegin(GL.GL_POINTS);
            {
                List<ClusterPathPoint> points = getPath();
                for (Point2D.Float p : points) {
                    gl.glVertex2f(p.x, p.y);
                }
            }
            gl.glEnd();

            // now draw velocityPPT vector
            if (showClusterVelocity) {
                gl.glLineWidth(VEL_LINE_WIDTH);
                gl.glBegin(GL.GL_LINES);
                {
                    gl.glVertex2i(x, y);
                    gl.glVertex2f(x + getVelocityPPT().x * VELOCITY_VECTOR_SCALING * velocityVectorScaling, y + getVelocityPPT().y * VELOCITY_VECTOR_SCALING * velocityVectorScaling);
                }
                gl.glEnd();
            }
            // text annoations on clusters, setup
            final int font = GLUT.BITMAP_HELVETICA_18;
            gl.glColor3f(1, 1, 1);
            gl.glRasterPos3f(location.x, location.y, 0);

            // draw radius text
//                            chip.getCanvas().getGlut().glutBitmapString(font, String.format("%.1f", getRadiusCorrectedForPerspective()));

            // annotate with instantaneousAngle (debug)
//                        chip.getCanvas().getGlut().glutBitmapString(font, String.format("%.0fdeg", instantaneousAngle*180/Math.PI));

            //annotate the cluster with the event rate computed as 1/(avg ISI) in keps
            if (showClusterEps) {
                float keps = getAvgEventRate() / (AEConstants.TICK_DEFAULT_US) * 1e3f;
                chip.getCanvas().getGlut().glutBitmapString(font, String.format("eps=%.0fk ", keps));
            }

            // annotate the cluster with hash ID
            if (showClusterNumber) {
                chip.getCanvas().getGlut().glutBitmapString(font, String.format("#=%d ", hashCode()));
            }

            //annotate the cluster with the velocityPPT in pps
            if (showClusterVelocity) {
                Point2D.Float velpps = getVelocityPPS();
                chip.getCanvas().getGlut().glutBitmapString(font, String.format("v=%.0fpps ", getSpeedPPS()));
            }

            if (showClusterMass) {
                chip.getCanvas().getGlut().glutBitmapString(font, String.format("m=%.1f ", getMassNow(lastUpdateTime)));
            }
        }

        /**
         * Computes a geometrical scale factor based on location of a point relative to the vanishing point.
         * If a pixel has been selected (we ask the renderer) then we compute the perspective from this vanishing point, otherwise
         * it is the top middle pixel.
         * @param p a point with 0,0 at lower left corner
         * @return scale factor, which grows linearly to 1 at botton of scene
         */
        final float getPerspectiveScaleFactor(Point2D.Float p) {
            if (!highwayPerspectiveEnabled) {
                return 1;
            }
            final float MIN_SCALE = 0.1f; // to prevent microclusters that hold only a single pixel
            if (!renderer.isPixelSelected()) {
                float scale = 1f - (p.y / chip.getSizeY()); // yfrac grows to 1 at bottom of image
                if (scale < MIN_SCALE) {
                    scale = MIN_SCALE;
                }
                return scale;
            } else {
                // scale is MIN_SCALE at vanishing point or above and grows linearly to 1 at max size of chip
                int size = chip.getMaxSize();
                float d = (float) p.distance(renderer.getXsel(), renderer.getYsel());
                float scale = d / size;
                if (scale < MIN_SCALE) {
                    scale = MIN_SCALE;
                }
                return scale;
            }
        }

        /** Constructs a cluster by merging two clusters.
         * All parameters of the resulting cluster should be reasonable combinations of the
         * source cluster parameters.
         * For example, the merged location values are weighted
         * by the mass of events that have supported each
         * source cluster, so that older clusters weigh more heavily
         * in the resulting cluster location. Subtle bugs or poor performance can result
         * from not properly handling the merging of parameters.
         *
         * @param one the first cluster
         * @param two the second cluster
         */
        public Cluster(Cluster one, Cluster two) {
            this();
            // merge locations by just averaging
//            location.x=(one.location.x+two.location.x)/2;
//            location.y=(one.location.y+two.location.y)/2;

            Cluster stronger = one.mass > two.mass ? one : two; //one.firstEventTimestamp < two.firstEventTimestamp ? one : two;
//            Cluster older=one.numEvents>two.numEvents? one:two;
            clusterNumber = stronger.clusterNumber;
            // merge locations by average weighted by mass of events supporting each cluster
            mass = one.mass + two.mass;
            numEvents = one.numEvents + two.numEvents;
            // change to older for location to avoid discontinuities in postion
            location.x = stronger.location.x; //(one.location.x * one.mass + two.location.x * two.mass) / (mass);
            location.y = stronger.location.y; // (one.location.y * one.mass + two.location.y * two.mass) / (mass);
            angle = stronger.angle;
            cosAngle = stronger.cosAngle;
            sinAngle = stronger.sinAngle;
            averageEventDistance = (one.averageEventDistance * one.mass + two.averageEventDistance * two.mass) / mass;
            averageEventXDistance = (one.averageEventXDistance * one.mass + two.averageEventXDistance * two.mass) / mass;
            averageEventYDistance = (one.averageEventYDistance * one.mass + two.averageEventYDistance * two.mass) / mass;

            lastEventTimestamp = one.lastEventTimestamp > two.lastEventTimestamp ? one.lastEventTimestamp : two.lastEventTimestamp;
            lastUpdateTime = lastEventTimestamp;
            lastPacketLocation.x = stronger.location.x;
            lastPacketLocation.y = stronger.location.y;
            firstEventTimestamp = stronger.firstEventTimestamp; // make lifetime the oldest src cluster
            path = stronger.path;
            birthLocation = stronger.birthLocation;
//            velocityFitter = stronger.velocityFitter;
            velocityPPT.x = stronger.velocityPPT.x;
            velocityPPT.y = stronger.velocityPPT.y;
            velocityPPS.x = stronger.velocityPPS.x;
            velocityPPS.y = stronger.velocityPPS.y;
            velocityValid = stronger.velocityValid;
            vxFilter = stronger.vxFilter;
            vyFilter = stronger.vyFilter;
            avgEventRate = stronger.avgEventRate;
            avgISI = stronger.avgISI;
            hasObtainedSupport = stronger.hasObtainedSupport;
            setAspectRatio(stronger.getAspectRatio());

//            Color c1=one.getColor(), c2=two.getColor();
            setColor(stronger.getColor());
//            System.out.println("merged "+one+" with "+two);
            //the radius should increase
//            setRadius((one.getRadius()+two.getRadius())/2);
            if (growMergedSizeEnabled) {
                float R = (one.getRadius() + two.getRadius()) / 2;
                setRadius(R + getMixingFactor() * R);
            } else {
                setRadius(stronger.getRadius());
            }
        }

        /** Creates a new Cluster using the event and generates a new output event which points back to the Cluster.
         *
         * @param ev the event to center the cluster on.
         * @param outItr used to generate the new event pointing back to the cluster.
         */
        private Cluster(BasicEvent ev, OutputEventIterator outItr) {
            this(ev);
            RectangularClusterTrackerEvent oe = (RectangularClusterTrackerEvent) outItr.nextOutput();
            oe.copyFrom(ev);
//            oe.setX((short) getLocation().x);
//            oe.setY((short) getLocation().y);
            oe.setCluster(this);
        }

        public int getLastEventTimestamp() {
//            EventXYType ev=events.get(events.size()-1);
//            return ev.timestamp;
            return lastEventTimestamp;
        }

        /** updates cluster by one event. The cluster velocityPPT is updated at the filterPacket level after all events
        in a packet are added.
        @param event the event
         */
        public void addEvent(BasicEvent event) {
            if ((event instanceof TypedEvent)) {
                TypedEvent e = (TypedEvent) event;
                if (useOnePolarityOnlyEnabled) {
                    if (useOffPolarityOnlyEnabled) {
                        if (e.type == 1) {
                            return;
                        }
                    } else {
                        if (e.type == 0) {
                            return;
                        }
                    }
                }
            }

            updateMass(event);
           

            float m = mixingFactor, m1 = 1 - m;

            float dt = event.timestamp - lastUpdateTime; // this timestamp may be bogus if it goes backwards in time, we need to check it later

            // if useVelocity is enabled, first update the location using the measured estimate of velocityPPT.
            // this will give predictor characteristic to cluster because cluster will move ahead to the predicted location of
            // the present event

            // don't do this now because the location is already updated by updateClusterLocations() TODO
//            if (useVelocity && dt > 0 && velocityFitter.valid) {
//                location.x = location.x + predictiveVelocityFactor * dt * velocityPPT.x;
//                location.y = location.y + predictiveVelocityFactor * dt * velocityPPT.y;
//            }

            // compute new cluster location by mixing old location with event location by using
            // mixing factor.  

            if ((event instanceof OrientationEvent)) {
                // if event is an orientation event, use the orientation to only move the cluster in a direction perpindicular to
                // the estimated orientation
                OrientationEvent eout = (OrientationEvent) event;
                OrientationEvent.UnitVector d = OrientationEvent.unitVectors[(eout.orientation + 2) % 4];
                //calculate projection
                float eventXCentered = event.x - location.x;
                float eventYCentered = event.y - location.y;
                float aDotB = (d.x * eventXCentered) + (d.y * eventYCentered);
                //float aDotA = (d.x * d.x) + (d.y *d.y);
                float division = aDotB; /// aDotA;
                float newX = (division * d.x) + location.x;
                float newY = (division * d.y) + location.y;

                location.x = (m1 * location.x + m * newX);
                location.y = (m1 * location.y + m * newY);
            } else {
                // otherwise, move the cluster in the direction of the event.
                location.x = (m1 * location.x + m * event.x);
                location.y = (m1 * location.y + m * event.y);
            }


            lastUpdateTime = event.timestamp;

            // velocityPPT of cluster is updated here as follows
            // 1. instantaneous velocityPPT is computed from old and new cluster locations and dt
            // 2. new velocityPPT is computed by mixing old velocityPPT with instaneous new velocityPPT using velocityMixingFactor
            // Since an event may pull the cluster back in the opposite direction it is moving, this measure is likely to be quite noisy.
            // It would be better to use the saved cluster locations after each packet is processed to perform an online regression
            // over the history of the cluster locations. Therefore we do not use the following anymore.
//            if(useVelocity && dt>0){
//                // update velocityPPT vector using old and new position only if valid dt
//                // and update it by the mixing factors
//                float oldvelx=velocityPPT.x;
//                float oldvely=velocityPPT.y;
//
//                float velx=(location.x-oldx)/dt; // instantaneous velocityPPT for this event in pixels/tick (pixels/us)
//                float vely=(location.y-oldy)/dt;
//
//                float vm1=1-velocityMixingFactor;
//                velocityPPT.x=vm1*oldvelx+velocityMixingFactor*velx;
//                velocityPPT.y=vm1*oldvely+velocityMixingFactor*vely;
//                velocityPPS.x=velocityPPT.x*VELPPS_SCALING;
//                velocityPPS.y=velocityPPT.y*VELPPS_SCALING;
//            }

            int prevLastTimestamp = lastEventTimestamp;
            lastEventTimestamp = event.timestamp;
            numEvents++;
            instantaneousISI = lastEventTimestamp - prevLastTimestamp;
            if (instantaneousISI <= 0) {
                instantaneousISI = 1;
            }
            avgISI = m1 * avgISI + m * instantaneousISI;
            instantaneousEventRate = 1f / instantaneousISI;
            avgEventRate = m1 * avgEventRate + m * instantaneousEventRate;

            averageEventDistance = m1 * averageEventDistance + m * distanceToLastEvent;
            averageEventXDistance = m1 * averageEventXDistance + m * xDistanceToLastEvent;
            averageEventYDistance = m1 * averageEventYDistance + m * yDistanceToLastEvent;

            // if scaling is enabled, now scale the cluster size
            scale(event);

        }

        /** Updates the cluster radius and angle according to distance of event from cluster center, but only if dynamicSizeEnabled or dynamicAspectRatioEnabled or dynamicAngleEnabled.
         * @param event the event to scale with
         */
        private final void scale(BasicEvent event) {
            if (dynamicSizeEnabled) {
                float dist = distanceTo(event);
                float oldr = radius;
                float newr = (1 - mixingFactor) * oldr + dist * mixingFactor;
                float f;
                if (newr > (f = defaultClusterRadius * MAX_SCALE_RATIO)) {
                    newr = f;
                } else if (newr < (f = defaultClusterRadius / MAX_SCALE_RATIO)) {
                    newr = f;
                }
                setRadius(newr);
            }
            if (dynamicAspectRatioEnabled) {
                // TODO aspect ratio must also account for dynamicAngleEnabled.
                float dx = (event.x - location.x);
                float dy = (event.y - location.y);
                float dw = dx * cosAngle + dy * sinAngle; // dot dx,dy with unit vector of instantaneousAngle of cluster
                float dh = -dx * sinAngle + dy * cosAngle; // and with normal to unit vector
                float oldAspectRatio = getAspectRatio();
                float newAspectRatio = Math.abs(dh / dw);
                if (dynamicAngleEnabled) {
                    if (newAspectRatio > ASPECT_RATIO_MAX_DYNAMIC_ANGLE_ENABLED) {
                        newAspectRatio = ASPECT_RATIO_MAX_DYNAMIC_ANGLE_ENABLED;
                    } else if (newAspectRatio < ASPECT_RATIO_MIN_DYNAMIC_ANGLE_ENABLED) {
                        newAspectRatio = ASPECT_RATIO_MIN_DYNAMIC_ANGLE_ENABLED;
                    }
                } else {
                    if (newAspectRatio > ASPECT_RATIO_MAX_DYNAMIC_ANGLE_DISABLED) {
                        newAspectRatio = ASPECT_RATIO_MAX_DYNAMIC_ANGLE_DISABLED;
                    } else if (newAspectRatio < ASPECT_RATIO_MIN_DYNAMIC_ANGLE_DISABLED) {
                        newAspectRatio = ASPECT_RATIO_MIN_DYNAMIC_ANGLE_DISABLED;
                    }
                }
                setAspectRatio((1 - mixingFactor) * oldAspectRatio + mixingFactor * newAspectRatio);
            }
            if (dynamicAngleEnabled) {
                // dynamically rotates cluster to line it up with edge.
                // the cluster instantaneousAngle is defined so horizontal edges have instantaneousAngle 0 or +/-PI, vertical have +/- PI/2.
                // instantaneousAngle increases CCW from 0 for rightward from center of cluster events.
                //
                // awkwardness here is that events will fall on either side around center of cluster.
                // instantaneousAngle of event is 0 or +/-PI when events are mostly horizontal (there is a cut at +/-PI from atan2).
                // similarly, if events are mostly vertical, then instantaneousAngle is either PI/2 or -PI/2.
                // if we just average instantaneous instantaneousAngle we get something in between which is at 90 deg
                // to actual instantaneousAngle of cluster.
                // if the event instantaneousAngle<0, we use PI-instantaneousAngle; this transformation makes all event angles fall from 0 to PI.
                // now the problem is that horizontal events still average to PI/2 (vertical cluster).

                float dx = (location.x - event.x);
                float dy = (location.y - event.y);
                float newAngle = (float) (Math.atan2(dy, dx));
                if (newAngle < 0) {
                    newAngle += (float) Math.PI; // puts newAngle in 0,PI, e.g -30deg becomes 150deg
                }
                // if newAngle is very different than established instantaneousAngle, assume it is
                // just the other end of the object and flip the newAngle.
//                boolean flippedPos=false, flippedNeg=false;
                float diff = newAngle - angle;
                if ((diff) > Math.PI / 2) {
                    // newAngle is clockwise a lot, flip it back across to
                    // negative value that can be averaged; e.g. instantaneousAngle=10, newAngle=179, newAngle->-1.
                    newAngle = newAngle - (float) Math.PI;
//                    flippedPos=true;
                } else if (diff < -Math.PI / 2) {
                    // newAngle is CCW
                    newAngle = -(float) Math.PI + newAngle; // instantaneousAngle=10, newAngle=179, newAngle->1
//                    flippedNeg=true;
                }
//                if(newAngle>3*Math.PI/4)
//                    newAngle=(float)Math.PI-newAngle;
                float angleDistance = (newAngle - angle); //angleDistance(instantaneousAngle, newAngle);
                // makes instantaneousAngle=0 for horizontal positive event, PI for horizontal negative event y=0+eps,x=-1, -PI for y=0-eps, x=-1, //
                // PI/2 for vertical positive, -Pi/2 for vertical negative event
                setAngle(angle + mixingFactor * angleDistance);
//                System.out.println(String.format("dx=%8.1f\tdy=%8.1f\tnewAngle=%8.1f\tangleDistance=%8.1f\tangle=%8.1f\tflippedPos=%s\tflippedNeg=%s",dx,dy,newAngle*180/Math.PI,angleDistance*180/Math.PI,instantaneousAngle*180/Math.PI,flippedPos,flippedNeg));
//                System.out.println(String.format("dx=%8.1f\tdy=%8.1f\tnewAngle=%8.1f\tangleDistance=%8.1f\tangle=%8.1f",dx,dy,newAngle*180/Math.PI,angleDistance*180/Math.PI,instantaneousAngle*180/Math.PI));
//                setAngle(-.1f);
            }
        }

        /**
         * Computes signed distance to-from between two angles with cut at -PI,PI. E.g.
         *     if e is from small instantaneousAngle and from=PI-e, to=-PI+e, then angular distance to-from is
         *     -2e rather than (PI-e)-(-PI+e)=2PI-2e.
         *     This minimum instantaneousAngle difference is useful to push an instantaneousAngle in the correct direction
         *     by the correct amount. For this example, we want to push an instantaneousAngle hovering around PI-e.
         *     We don't want angles of -PI+e to push the instantaneousAngle from lot, just from bit towards PI.
         *     If we have instantaneousAngle <code>from</code> and new instantaneousAngle <code>to</code> and
         *     mixing factor m<<1, then new instantaneousAngle <code>c=from+m*angleDistance(from,to)</code>.
         *
         *
         * @param from the first instantaneousAngle
         * @param to the second instantaneousAngle
         * @return the smallest difference to-from, ordinarily positive if to>from
         */
        private float angleDistance(float from, float to) {
            float d = to - from;
            if (d > Math.PI) {
                return d - (float) Math.PI;
            }
            if (d < -Math.PI) {
                return d + (float) Math.PI;
            }
            return d;
        }

        /** Measures distance from cluster center to event.
         * @return distance of this cluster to the event in manhatten (cheap) metric (sum of abs values of x and y distance).
         */
        private float distanceTo(BasicEvent event) {
            final float dx = event.x - location.x;
            final float dy = event.y - location.y;
//            return Math.abs(dx)+Math.abs(dy);
            return distanceMetric(dx, dy);
//            dx*=dx;
//            dy*=dy;
//            float distance=(float)Math.sqrt(dx+dy);
//            return distance;
        }

        /** Returns the implemented distance metric which is the Manhattan distance for speed.
         * This is the sum of abs(dx)+abs(dy).
         * @param dx the x distance
         * @param dy the y distance
         * @return abs(dx)+abs(dy)
         */
        public float distanceMetric(float dx, float dy) {
            return ((dx > 0) ? dx : -dx) + ((dy > 0) ? dy : -dy);
        }

        /** Measures distance in x direction, accounting for 
         * instantaneousAngle of cluster and predicted movement of cluster.
         *
         * @return distance in x direction of this cluster to the event,
         * where x is measured along instantaneousAngle=0.
         */
        private float distanceToX(BasicEvent event) {
            int dt = event.timestamp - lastUpdateTime;
            float distance = Math.abs((event.x - location.x + velocityPPT.x * (dt)) * cosAngle + (event.y - location.y + velocityPPT.y * (dt)) * sinAngle);
//            float distance = Math.abs (event.x - location.x);
            return distance;
        }

        /** Measures distance in y direction, accounting for instantaneousAngle of cluster, 
         * where y is measured along instantaneousAngle=Pi/2  and predicted movement of cluster
         * 
         * @return distance in y direction of this cluster to the event
         */
        private float distanceToY(BasicEvent event) {
            int dt = event.timestamp - lastUpdateTime;
            float distance = Math.abs((event.y - location.y + velocityPPT.y * (dt)) * cosAngle - (event.x - location.x + velocityPPT.x * (dt)) * sinAngle);
///           float distance = Math.abs (event.y - location.y);
            return distance;
        }

        /** Computes and returns distance to another cluster.
         * @return distance of this cluster to the other cluster in pixels.
         */
        protected final float distanceTo(Cluster c) {
            // TODO doesn't use predicted location of clusters, only present locations
            float dx = c.location.x - location.x;
            float dy = c.location.y - location.y;
            return distanceMetric(dx, dy);
//            if(dx<0)dx=-dx;
//            if(dy<0)dy=-dy;
//            dx*=dx;
//            dy*=dy;
//            float distance=(float)Math.sqrt(dx+dy);
//            distance=dx+dy;
//            return distance;
        }

        /** Computes and returns the angle of this cluster's velocityPPT vector to another cluster's velocityPPT vector.
         *
         * @param c the other cluster.
         * @return the angle in radians, from 0 to PI in radians. If either cluster has zero velocityPPT, returns 0.
         */
        protected final float velocityAngleToRad(Cluster c) {
            float s1 = getSpeedPPS(), s2 = c.getSpeedPPS();
            if (s1 == 0 || s2 == 0) {
                return 0;
            }
            float dot = velocityPPS.x * c.velocityPPS.x + velocityPPS.y * c.velocityPPS.y;
            float angleRad = (float) Math.acos(dot / s1 / s2);
            return angleRad;
        }

        /**
         * Computes and returns the total absolute distance
         * (shortest path) traveled in pixels since the birth of this cluster
         * @return distance in pixels since birth of cluster
         */
        public float getDistanceFromBirth() {
            double dx = location.x - birthLocation.x;
            double dy = location.y - birthLocation.y;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        /** @return signed distance in Y from birth. */
        public float getDistanceYFromBirth() {
            return location.y - birthLocation.y;
        }

        /** @return signed distance in X from birth. */
        public float getDistanceXFromBirth() {
            return location.x - birthLocation.x;
        }

        /** Corrects for perspective looking down on a flat surface towards a horizon.
        @return the absolute size of the cluster after perspective correction, i.e., a large cluster at the bottom
         * of the scene is the same absolute size as a smaller cluster higher up in the scene.
         */
        public float getRadiusCorrectedForPerspective() {
            float scale = 1 / getPerspectiveScaleFactor(location);
            return radius * scale;
        }

        /** The effective radius of the cluster depends on whether highwayPerspectiveEnabled is true or not and also
        on the surround of the cluster. The getRadius value is not used directly since it is a parameter that is combined
        with perspective location and aspect ratio.

        @return the cluster radius.
         */
        public final float getRadius() {
            return radius;
        }

        /** the radius of a cluster is the distance in pixels from the cluster center
         * that is the putative model size.
         * If highwayPerspectiveEnabled is true, then the radius is set to a fixed size
         * depending on the defaultClusterRadius and the perspective
         * location of the cluster and r is ignored. The aspect ratio parameters
         * radiusX and radiusY of the cluster are also set.
         * @param r the radius in pixels
         */
        public void setRadius(float r) {
            if (!highwayPerspectiveEnabled) {
                radius = r;
            } else {
                radius = defaultClusterRadius * getPerspectiveScaleFactor(location);
            }
            radiusX = radius / aspectRatio;
            radiusY = radius * aspectRatio;
        }

        final public Point2D.Float getLocation() {
            return location;
        }

        public void setLocation(Point2D.Float l) {
            this.location = l;
        }

        /** Flags whether cluster has gotten enough support. This flag is sticky and will be true from when the cluster
        has gotten sufficient support and has enough velocityPPT (when using velocityPPT).
        When the cluster is first marked visible, it's birthLocation is set to the current location.
        @return true if cluster has enough support.
         */
        final public boolean isVisible() {
//            if (hasObtainedSupport) {
//                return true;
//            }
            boolean ret = true;
            if (numEvents < thresholdEventsForVisibleCluster) {
                ret = false;
            }
            if (pathsEnabled) {
                double speed = Math.sqrt(velocityPPT.x * velocityPPT.x + velocityPPT.y * velocityPPT.y) * 1e6 / AEConstants.TICK_DEFAULT_US; // speed is in pixels/sec
                if (speed < thresholdVelocityForVisibleCluster) {
                    ret = false;
                }
            }
            hasObtainedSupport = ret;
            if (ret) {
                birthLocation.x = location.x;
                birthLocation.y = location.y;  // reset location of birth to presumably less noisy current location.
            }
            return ret;
        }

        /** Flags whether this cluster was ever 'visible', i.e., had ever obtained sufficient support to be marked visible.
         *
         * @return true if it was ever visible.
         */
        final public boolean isWasEverVisible() {
            return hasObtainedSupport;
        }

        /** @return lifetime of cluster in timestamp ticks, measured as lastUpdateTime-firstEventTimestamp. */
        final public int getLifetime() {
            return lastUpdateTime - firstEventTimestamp;
        }

        /** Updates path (historical) information for this cluster, 
         * including cluster velocity (by calling updateVelocity()).
         * The path is trimmed to maximum length if logging is not enabled.
         * @param t current timestamp.
         */
        final public void updatePath(int t) {
            if (!pathsEnabled && !useVelocity) {
                return;
            }
            if (numEvents == previousNumEvents) {
                return; // don't add point unless we had events that caused change in path (aside from prediction from velocityPPT)
            }
            path.add(new ClusterPathPoint(location.x, location.y, t, numEvents - previousNumEvents));
            previousNumEvents = numEvents;
            updateVelocity();

            if (path.size() > pathLength) {
                if (!logDataEnabled || clusterLoggingMethod != clusterLoggingMethod.LogClusters) {
//                    path.remove(path.get(0)); // if we're logging cluster paths, then save all cluster history regardless of pathLength
                    path.remove(path.get(0)); // if we're logging cluster paths, then save all cluster history regardless of pathLength
                }
            }
        }

        /** Updates velocityPPT, velocityPPS of cluster and last path point lowpass filtered velocity.
         *
         */
        private void updateVelocity() {
//            velocityFitter.update();
//            if (velocityFitter.valid) {
//                velocityPPT.x = velocityFitter.getXVelocity();
//                velocityPPT.y = velocityFitter.getYVelocity();
//                velocityPPS.x = velocityPPT.x * VELPPS_SCALING;
//                velocityPPS.y = velocityPPT.y * VELPPS_SCALING;
//                velocityValid = true;
//            } else {
//                velocityValid = false;
//            }

            // update velocityPPT of cluster using last two path points
            if (path.size() > 1) {
                ClusterPathPoint c1 = path.get(path.size() - 2);
                ClusterPathPoint c2 = path.get(path.size() - 1);
                int dt = c2.t - c1.t;
                if (dt > MIN_DT_FOR_VELOCITY_UPDATE) {
                    float vx = (c2.x - c1.x) / dt;
                    float vy = (c2.y - c1.y) / dt;
                    velocityPPT.x = vxFilter.filter(vx, lastEventTimestamp);
                    velocityPPT.y = vyFilter.filter(vy, lastEventTimestamp);
                    c2.velocityPPT = new Point2D.Float(velocityPPT.x, velocityPPT.y);
//                    float m1=1-velocityMixingFactor;
//                    velocityPPT.x=m1*velocityPPT.x+velocityMixingFactor*vx;
//                    velocityPPT.y=m1*velocityPPT.y+velocityMixingFactor*vy;
                    velocityPPS.x = velocityPPT.x * VELPPS_SCALING;
                    velocityPPS.y = velocityPPT.y * VELPPS_SCALING;
                    setVelocityValid(true);
                }
            }
        }

        @Override
        public String toString() {
            return String.format("Cluster number=#%d numEvents=%d locationX=%d locationY=%d radiusX=%.1f radiusY=%.1f lifetime=%d visible=%s speedPPS=%.2f",
                    getClusterNumber(), numEvents,
                    (int) location.x,
                    (int) location.y,
                    radiusX,
                    radiusY,
                    getLifetime(),
                    isVisible(),
                    getSpeedPPS());
        }

        @Override
        public java.util.List<ClusterPathPoint> getPath() {
            return path;
        }

        public Color getColor() {
            return color;
        }

        public void setColor(Color color) {
            this.color = color;
        }

        /** Returns velocityPPT of cluster in pixels per second.
         * 
         * @return averaged velocityPPT of cluster in pixels per second.
         * <p>
         * The method of measuring velocityPPT is based on a linear regression of a number of previous cluter locations.
         * @see #getVelocityPPT()
         *
         */
        @Override
        public Point2D.Float getVelocityPPS() {
            return velocityPPS;
            /* old method for velocityPPT estimation is as follows
             * The velocityPPT is instantaneously
             * computed from the movement of the cluster caused by the last event, then this velocityPPT is mixed
             * with the the old velocityPPT by the mixing factor. Thus the mixing factor is appplied twice: once for moving
             * the cluster and again for changing the velocityPPT.
             * */
        }

        /** Computes and returns speed of cluster in pixels per second.
         *
         * @return speed in pixels per second.
         */
        @Override
        public float getSpeedPPS() {
            return (float) Math.sqrt(velocityPPS.x * velocityPPS.x + velocityPPS.y * velocityPPS.y);
        }

        /** Computes and returns speed of cluster in pixels per timestamp tick.
         *
         * @return speed in pixels per timestamp tick.
         */
        public float getSpeedPPT() {
            return (float) Math.sqrt(velocityPPT.x * velocityPPT.x + velocityPPT.y * velocityPPT.y);
        }

        /** @return average (mixed by {@link #mixingFactor}) distance from events to cluster center
         */
        public float getAverageEventDistance() {
            return averageEventDistance;
        }

        /** @see #getAverageEventDistance */
        public void setAverageEventDistance(float averageEventDistance) {
            this.averageEventDistance = averageEventDistance;
        }

        public float getAverageEventXDistance() {
            return averageEventXDistance;
        }

        public void setAverageEventXDistance(float averageEventXDistance) {
            this.averageEventXDistance = averageEventXDistance;
        }

        public float getAverageEventYDistance() {
            return averageEventYDistance;
        }

        public void setAverageEventYDistance(float averageEventYDistance) {
            this.averageEventYDistance = averageEventYDistance;
        }

        public float getMeasuredAspectRatio() {
            return averageEventYDistance / averageEventXDistance;
        }

        public float getMeasuredArea() {
            return averageEventYDistance * averageEventXDistance;
        }

        public float getMeasuredRadius() {
            return (float) Math.sqrt(averageEventYDistance * averageEventYDistance + averageEventXDistance * averageEventXDistance);
        }

        public float getMeasuredAverageEventRate() {
            return avgEventRate / radius;
        }

        /** Computes the size of the cluster based on average event distance and adjusted for perpective scaling.
         * A large cluster at botton of screen is the same size as a smaller cluster closer to horizon
         * @return size of cluster in pizels
         */
        public float getMeasuredSizeCorrectedByPerspective() {
            float scale = getPerspectiveScaleFactor(location);
            if (scale <= 0) {
                return averageEventDistance;
            }
            return averageEventDistance / scale;
        }

        /** Sets color according to measured cluster size */
        public void setColorAccordingToSize() {
            float s = getMeasuredSizeCorrectedByPerspective();
            float hue = 2 * s / chip.getMaxSize();
            if (hue > 1) {
                hue = 1;
            }
            setColor(Color.getHSBColor(hue, 1f, 1f));
        }

        /** Sets color according to age of cluster */
        public void setColorAccordingToAge() {
            float brightness = (float) Math.max(0f, Math.min(1f, getLifetime() / fullbrightnessLifetime));
            Color c = Color.getHSBColor(.5f, 1f, brightness);
            setColor(c);
        }

//        public void setColorAccordingToClass(){
//            float s=getMeasuredSizeCorrectedByPerspective();
//            float hue=0.5f;
//            if(s>getClassifierThreshold()){
//                hue=.3f;
//            }else{
//                hue=.8f;
//            }
//            Color c=Color.getHSBColor(hue,1f,1f);
//            setColor(c);
//        }
        public void setColorAutomatically() {
            if (isColorClustersDifferentlyEnabled()) {
                // color is set on object creation, don't change it
            } else {
                setColorAccordingToSize();
            }
//            else if(!isClassifierEnabled()){
//                setColorAccordingToSize(); // sets color according to measured cluster size, corrected by perspective, if this is enabled
//            // setColorAccordingToAge(); // sets color according to how long the cluster has existed
//            }else{ // classifier enabled
//                setColorAccordingToClass();
//            }
        }

        public int getClusterNumber() {
            return clusterNumber;
        }

        public void setClusterNumber(int clusterNumber) {
            this.clusterNumber = clusterNumber;
        }

        /** @return average ISI for this cluster in timestamp ticks. Average is computed using cluster location mising factor.
         */
        public float getAvgISI() {
            return avgISI;
        }

        public void setAvgISI(float avgISI) {
            this.avgISI = avgISI;
        }

        /** @return average event rate in spikes per timestamp tick. Average is computed using location mixing factor. Note that this measure
         * emphasizes the high spike rates because a few events in rapid succession can rapidly push up the average rate.
         */
        public float getAvgEventRate() {
            return avgEventRate;
        }

        public void setAvgEventRate(float avgEventRate) {
            this.avgEventRate = avgEventRate;
        }

        public float getAspectRatio() {
            return aspectRatio;
        }

        /** Aspect ratio is 1 for square cluster and in general is height/width.
         *
         * @param aspectRatio
         */
        public void setAspectRatio(float aspectRatio) {
            this.aspectRatio = aspectRatio;
//            float radiusX=radius/aspectRatio, radiusY=radius*aspectRatio;
        }

        /** Angle of cluster, in radians.
         *
         * @return in radians.
         */
        public float getAngle() {
            return angle;
        }

        /** Angle of cluster is zero by default and increases CCW from 0 lying along the x axis.
         * Also sets internal cosAngle and sinAngle.
         * @param angle in radians.
         */
        public void setAngle(float angle) {
            this.angle = angle;
            cosAngle = (float) Math.cos(angle);
            sinAngle = 1 - cosAngle * cosAngle;
        }

        /**
         * Does a moving or rolling linear regression (a linear fit) on updated PathPoint data.
         * The new data point replaces the oldest data point. Summary statistics holds the rollling values
         * and are updated by subtracting the oldest point and adding the newest one.
         * From <a href="http://en.wikipedia.org/wiki/Ordinary_least_squares#Summarizing_the_data">Wikipedia article on Ordinary least squares</a>.
         *<p>
        If velocityPPT cannot be estimated (e.g. due to only 2 identical points) it is not updated.
         * @author tobi
         */
        private class RollingVelocityFitter {

            private static final int LENGTH_DEFAULT = 5;
            private int length = LENGTH_DEFAULT;
            private float st = 0, sx = 0, sy = 0, stt = 0, sxt = 0, syt = 0, den = 1; // summary stats
            private ArrayList<ClusterPathPoint> points;
            private float xVelocity = 0, yVelocity = 0;
            private boolean valid = false;
            private int nPoints = 0;

            /** Creates a new instance of RollingLinearRegression */
            public RollingVelocityFitter(ArrayList<ClusterPathPoint> points, int length) {
                this.points = points;
                this.length = length;
            }

            @Override
            public String toString() {
                return String.format("RollingVelocityFitter: \n" + "valid=%s nPoints=%d\n" +
                        "xVel=%f, yVel=%f\n" +
                        "st=%f sx=%f sy=%f, sxt=%f syt=%f den=%f",
                        valid, nPoints,
                        xVelocity, yVelocity,
                        st, sx, sy, sxt, syt, den);

            }

            /**
             * Updates estimated velocityPPT based on last point in path. If velocityPPT cannot be estimated
            it is not updated.
             * @param t current timestamp.
             */
            private synchronized void update() {
                int n = points.size();
                if (n < 1) {
                    return;
                }
                ClusterPathPoint p = points.get(n - 1); // take last point
                if (p.getNEvents() == 0) {
                    return;
                }
                nPoints++;
                if (n > length) {
                    removeOldestPoint(); // discard data beyond range length
                }
                n = n > length ? length : n;  // n grows to max length
                float dt = p.t - firstEventTimestamp; // t is time since cluster formed, limits absolute t for numerics
                st += dt;
                sx += p.x;
                sy += p.y;
                stt += dt * dt;
                sxt += p.x * dt;
                syt += p.y * dt;
//                if(n<length) return; // don't estimate velocityPPT until we have all necessary points, results very noisy and send cluster off to infinity very often, would give NaN
                den = (n * stt - st * st);
                if (n >= length && den != 0) {
                    valid = true;
                    xVelocity = (n * sxt - st * sx) / den;
                    yVelocity = (n * syt - st * sy) / den;
                } else {
                    valid = false;
                }
//                System.out.println(this.toString());
            }

            private void removeOldestPoint() {
                // takes away from summary states the oldest point
                ClusterPathPoint p = points.get(points.size() - length - 1);
                // if points has 5 points (0-4), length=3, then remove points(5-3-1)=points(1) leaving 2-4 which is correct
                float dt = p.t - firstEventTimestamp;
                st -= dt;
                sx -= p.x;
                sy -= p.y;
                stt -= dt * dt;
                sxt -= p.x * dt;
                syt -= p.y * dt;
            }

            int getLength() {
                return length;
            }

            /** Sets the window length.  Clears the accumulated data.
             * @param length the number of points to fit
             * @see #LENGTH_DEFAULT
             */
            synchronized void setLength(int length) {
                this.length = length;
            }

            public float getXVelocity() {
                return xVelocity;
            }

            public float getYVelocity() {
                return yVelocity;
            }

            /** Returns true if the last estimate resulted in a valid measurement
             * (false when e.g. there are only two identical measurements)
             */
            public boolean isValid() {
                return valid;
            }

            public void setValid(boolean valid) {
                this.valid = valid;
            }
        } // rolling velocityPPT fitter

        /** Returns birth location of cluster: initially the first event and later, after cluster
         * becomes visible, it is the location when it becomes visible, which is presumably less noisy.
         *
         * @return x,y location.
         */
        public Point2D.Float getBirthLocation() {
            return birthLocation;
        }

        /** Returns first timestamp of cluster; this time is updated when cluster becomes visible.
         *
         * @return timestamp of birth location.
         */
        public int getBirthTime() {
            return firstEventTimestamp;
        }

        public void setBirthLocation(Point2D.Float birthLocation) {
            this.birthLocation = birthLocation;
        }

        /** This flog is set true after a velocityPPT has been computed for the cluster.
         * This may take several packets.

        @return true if valid.
         */
        public boolean isVelocityValid() {
            return velocityValid;
        }

        public void setVelocityValid(boolean velocityValid) {
            this.velocityValid = velocityValid;
        }
    } // Cluster

    public java.util.List<RectangularClusterTracker.Cluster> getClusters() {
        return this.clusters;
    }

    private LinkedList<RectangularClusterTracker.Cluster> getPruneList() {
        return this.pruneList;
    }
    protected static final float fullbrightnessLifetime = 1000000;
    /** Useful for subclasses. */
    protected Random random = new Random();

    private final void drawCluster(final Cluster c, float[][][] fr) {
        int x = (int) c.getLocation().x;
        int y = (int) c.getLocation().y;


        int sy = (int) c.getRadius(); // sx sy are (half) size of rectangle
        int sx = sy;
        int ix, iy;
        int mn, mx;

        if (isColorClustersDifferentlyEnabled()) {
        } else {
            c.setColorAccordingToSize();
        }

        Color color = c.getColor();
        if (true) { // draw boxes
            iy = y - sy;    // line under center
            mn = x - sx;
            mx = x + sx;
            for (ix = mn; ix <= mx; ix++) {
                colorPixel(ix, iy, fr, clusterColorChannel, color);
            }
            iy = y + sy;    // line over center
            for (ix = mn; ix <= mx; ix++) {
                colorPixel(ix, iy, fr, clusterColorChannel, color);
            }
            ix = x - sx;        // line to left
            mn = y - sy;
            mx = y + sy;
            for (iy = mn; iy <= mx; iy++) {
                colorPixel(ix, iy, fr, clusterColorChannel, color);
            }
            ix = x + sx;    // to right
            for (iy = mn; iy <= mx; iy++) {
                colorPixel(ix, iy, fr, clusterColorChannel, color);
            }
        } else { // draw diamond reflecting manhatten distance measure doesn't look very nice because not antialiased at all
            iy = y - sy;    // line up right from bot
            ix = x;
            mx = x + sx;
            while (ix < mx) {
                colorPixel(ix++, iy++, fr, clusterColorChannel, color);
            }
            mx = x + sx;
            ix = x;
            iy = y + sy;    // line down right from top
            while (ix < mx) {
                colorPixel(ix++, iy--, fr, clusterColorChannel, color);
            }
            ix = x;        // line from top down left
            iy = y + sy;
            while (iy >= y) {
                colorPixel(ix--, iy--, fr, clusterColorChannel, color);
            }
            ix = x;
            iy = y - sy;
            while (iy < y) {
                colorPixel(ix--, iy++, fr, clusterColorChannel, color);
            }
        }

        List<ClusterPathPoint> points = c.getPath();
        for (Point2D.Float p : points) {
            colorPixel(Math.round(p.x), Math.round(p.y), fr, clusterColorChannel, color);
        }

    }
    private static final int clusterColorChannel = 2;

    /** @param x x location of pixel
     *@param y y location
     *@param fr the frame data
     *@param channel the RGB channel number 0-2
     *@param brightness the brightness 0-1
     */
    private final void colorPixel(final int x, final int y, final float[][][] fr, int channel, Color color) {
        if (y < 0 || y > fr.length - 1 || x < 0 || x > fr[0].length - 1) {
            return;
        }
        float[] rgb = color.getRGBColorComponents(null);
        float[] f = fr[y][x];
        for (int i = 0; i < 3; i++) {
            f[i] = rgb[i];
        }
//        fr[y][x][channel]=brightness;
////        if(brightness<1){
//        for(int i=0;i<3;i++){
//            if(i!=channel) fr[y][x][i]=0;
//        }
////        }
    }

    /** lifetime of cluster in ms without support */
    public final int getClusterLifetimeWithoutSupportUs() {
        return clusterLifetimeWithoutSupportUs;
    }

    /** lifetime of cluster in ms without support */
    public void setClusterLifetimeWithoutSupportUs(final int clusterLifetimeWithoutSupport) {
        this.clusterLifetimeWithoutSupportUs = clusterLifetimeWithoutSupport;
        getPrefs().putInt("RectangularClusterTracker.clusterLifetimeWithoutSupport", clusterLifetimeWithoutSupport);
    }

    /** max distance from cluster to event as fraction of size of array */
    public final float getClusterSize() {
        return clusterSize;
    }

    /** sets max distance from cluster center to event as fraction of maximum size of chip pixel array.
     * e.g. clusterSize=0.5 and 128x64 array means cluster has radius of 0.5*128=64 pixels.
     *
     * @param clusterSize
     */
    synchronized public void setClusterSize(float clusterSize) {
        if (clusterSize > 1f) {
            clusterSize = 1f;
        }
        if (clusterSize < 0) {
            clusterSize = 0;
        }
        float old=this.clusterSize;
        defaultClusterRadius = (int) Math.max(chip.getSizeX(), chip.getSizeY()) * clusterSize;
        this.clusterSize = clusterSize;
        for (Cluster c : clusters) {
            c.setRadius(defaultClusterRadius);
        }
        getPrefs().putFloat("RectangularClusterTracker.clusterSize", clusterSize);
        support.firePropertyChange("clusterSize", old, clusterSize);
    }

    public float getMinClusterSize() {
        return 0;
    }

    public float getMaxClusterSize() {
        return .3f;
    }

    /** max number of clusters */
    public final int getMaxNumClusters() {
        return maxNumClusters;
    }

    public int getMinMaxNumClusters() {
        return 0;
    }

    public int getMaxMaxNumClusters() {
        return 20;
    }

    /** max number of clusters */
    public void setMaxNumClusters(final int maxNumClusters) {
        this.maxNumClusters = maxNumClusters;
        getPrefs().putInt("RectangularClusterTracker.maxNumClusters", maxNumClusters);
    }

//    /** number of events to store for a cluster */
//    public int getNumEventsStoredInCluster() {
//        return prefs.getInt("RectangularClusterTracker.numEventsStoredInCluster",10);
//    }
//
//    /** number of events to store for a cluster */
//    public void setNumEventsStoredInCluster(final int numEventsStoredInCluster) {
//        prefs.putInt("RectangularClusterTracker.numEventsStoredInCluster", numEventsStoredInCluster);
//    }
    /** number of events to make a potential cluster visible */
    public final int getThresholdEventsForVisibleCluster() {
        return thresholdEventsForVisibleCluster;
    }

    /** number of events to make a potential cluster visible */
    public void setThresholdEventsForVisibleCluster(final int thresholdEventsForVisibleCluster) {
        this.thresholdEventsForVisibleCluster = thresholdEventsForVisibleCluster;
        getPrefs().putInt("RectangularClusterTracker.thresholdEventsForVisibleCluster", thresholdEventsForVisibleCluster);
    }

    public Object getFilterState() {
        return null;
    }

    private boolean isGeneratingFilter() {
        return false;
    }

    synchronized public void resetFilter() {
        // before reset, log all the clusters that remain
        if (logDataEnabled && clusterLoggingMethod == ClusterLoggingMethod.LogClusters) {
            clusterLogger.logClusterHistories(clusters);
        }
        clusters.clear();
        clusterCounter = 0;
        logFrameNumber = 0;
        averageVelocityPPT.x = 0;
        averageVelocityPPT.y = 0;
    }

    /** Processes the incoming events to output RectangularClusterTrackerEvent's.
     *
     * @param in
     * @return packet of RectangularClusterTrackerEvent.
     */
    public EventPacket<?> filterPacket(EventPacket<?> in) {
//        EventPacket out; // TODO check use of out packet here, doesn't quite make sense
        if(filterEventsEnabled) checkOutputPacketEventType(RectangularClusterTrackerEvent.class);
        if (enclosedFilter != null) {
            in = enclosedFilter.filterPacket(in);
            out = track(out);
            return out;
        } else {
            out = track((EventPacket<BasicEvent>) in);
            return out;
        }
    }

    public boolean isHighwayPerspectiveEnabled() {
        return highwayPerspectiveEnabled;
    }

    public void setHighwayPerspectiveEnabled(boolean highwayPerspectiveEnabled) {
        this.highwayPerspectiveEnabled = highwayPerspectiveEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.highwayPerspectiveEnabled", highwayPerspectiveEnabled);
    }

    public float getMixingFactor() {
        return mixingFactor;
    }

    public void setMixingFactor(float mixingFactor) {
        if (mixingFactor < 0) {
            mixingFactor = 0;
        }
        if (mixingFactor > 1) {
            mixingFactor = 1f;
        }
        this.mixingFactor = mixingFactor;
        getPrefs().putFloat("RectangularClusterTracker.mixingFactor", mixingFactor);
    }

    /** Implemeting getMin and getMax methods constucts a slider control for the mixing factor in the FilterPanel.
     *
     * @return 0
     */
    public float getMinMixingFactor() {
        return 0;
    }

    /**
     * Constrains upper value of slider.
     *
     * @return max value for slider - text box can chooser larger value.
     */
    public float getMaxMixingFactor() {
        return .02f;
    }

    /** @see #setSurround */
    public float getSurround() {
        return surround;
    }

    public float getMinSurround() {
        return 1;
    }

    public float getMaxSurround() {
        return 30;
    }

    /** sets scale factor of radius that events outside the cluster size can affect the size of the cluster if
     * {@link #setDynamicSizeEnabled scaling} is enabled.
     * @param surround the scale factor, constrained >1 by setter. radius is multiplied by this to determine if event is within surround.
     */
    public void setSurround(float surround) {
        if (surround < 1) {
            surround = 1;
        }
        this.surround = surround;
        getPrefs().putFloat("RectangularClusterTracker.surround", surround);
    }

    /** @see #setPathsEnabled
     */
    public boolean isPathsEnabled() {
        return pathsEnabled;
    }

    /**
     * Enable cluster history paths. The path of each cluster is stored as a list of points at the end of each cluster list update.
     * This option is required (and set true) if useVelocity is set true.
     *
     * @param pathsEnabled true to show the history of the cluster locations on each packet.
     */
    public void setPathsEnabled(boolean pathsEnabled) {
        support.firePropertyChange("pathsEnabled", this.pathsEnabled, pathsEnabled);
        this.pathsEnabled = pathsEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.pathsEnabled", pathsEnabled);
    }

    /** @see #setDynamicSizeEnabled
     */
    public boolean getDynamicSizeEnabled() {
        return dynamicSizeEnabled;
    }

    /**
     * Enables cluster size scaling. The clusters are dynamically resized by the distances of the events from the cluster center. If most events
     * are far from the cluster then the cluster size is increased, but if most events are close to the cluster center than the cluster size is
     * decreased. The size change for each event comes from mixing the old size with a the event distance from the center using the mixing factor.
     * @param dynamicSizeEnabled true to enable scaling of cluster size
     */
    public void setDynamicSizeEnabled(boolean dynamicSizeEnabled) {
        this.dynamicSizeEnabled = dynamicSizeEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.dynamicSizeEnabled", dynamicSizeEnabled);
    }

    /**@see #setColorClustersDifferentlyEnabled */
    public boolean isColorClustersDifferentlyEnabled() {
        return colorClustersDifferentlyEnabled;
    }

    /** @param colorClustersDifferentlyEnabled true to color each cluster a different color. false to color each cluster
     * by its age
     */
    public void setColorClustersDifferentlyEnabled(boolean colorClustersDifferentlyEnabled) {
        this.colorClustersDifferentlyEnabled = colorClustersDifferentlyEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.colorClustersDifferentlyEnabled", colorClustersDifferentlyEnabled);
    }

    /**
     * @return the filterEventsEnabled
     */
    public boolean isFilterEventsEnabled() {
        return filterEventsEnabled;
    }

    /**
     * @param filterEventsEnabled the filterEventsEnabled to set
     */
    synchronized public void setFilterEventsEnabled(boolean filterEventsEnabled) {
        boolean old = this.filterEventsEnabled;
        this.filterEventsEnabled = filterEventsEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.filterEventsEnabled", filterEventsEnabled);
        support.firePropertyChange("filterEventsEnabled", old, filterEventsEnabled);
    }

    public void update(Observable o, Object arg) {
        if (o == this) {
            UpdateMessage msg = (UpdateMessage) arg;
            updateClusterList(msg.packet, msg.timestamp);
        } else if (o instanceof AEChip) {
            initFilter();
        }
    }

    public boolean isUseOnePolarityOnlyEnabled() {
        return useOnePolarityOnlyEnabled;
    }

    public void setUseOnePolarityOnlyEnabled(boolean useOnePolarityOnlyEnabled) {
        this.useOnePolarityOnlyEnabled = useOnePolarityOnlyEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.useOnePolarityOnlyEnabled", useOnePolarityOnlyEnabled);
    }

    public boolean isUseOffPolarityOnlyEnabled() {
        return useOffPolarityOnlyEnabled;
    }

    public void setUseOffPolarityOnlyEnabled(boolean useOffPolarityOnlyEnabled) {
        this.useOffPolarityOnlyEnabled = useOffPolarityOnlyEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.useOffPolarityOnlyEnabled", useOffPolarityOnlyEnabled);
    }

    protected void drawBox(GL gl, int x, int y, int sx, int sy, float angle) {
        final float r2d = (float) (180 / Math.PI);
        gl.glPushMatrix();
        gl.glTranslatef(x, y, 0);
        gl.glRotatef(angle * r2d, 0, 0, 1);
        gl.glBegin(GL.GL_LINE_LOOP);
        {
            gl.glVertex2i(-sx, -sy);
            gl.glVertex2i(+sx, -sy);
            gl.glVertex2i(+sx, +sy);
            gl.glVertex2i(-sx, +sy);
        }
        gl.glEnd();
        if (dynamicAngleEnabled) {
            gl.glBegin(GL.GL_LINES);
            {
                gl.glVertex2i(0, 0);
                gl.glVertex2i(sx, 0);
            }
            gl.glEnd();
        }
        gl.glPopMatrix();
    }

    synchronized public void annotate(GLAutoDrawable drawable) {
        if (!isFilterEnabled()) {
            return;
        }
        final float BOX_LINE_WIDTH = 2f; // in chip
        final float PATH_LINE_WIDTH = .5f;
        final float VEL_LINE_WIDTH = 4f;
        final float PATH_POINT_SIZE = 4f;
        GL gl = drawable.getGL(); // when we get this we are already set up with scale 1=1 pixel, at LL corner
        if (gl == null) {
            log.warning("null GL in RectangularClusterTracker.annotate");
            return;
        }
        float[] rgb = new float[4];
        gl.glPushMatrix();
        try {
            {
                for (Cluster c : clusters) {
                    if (showAllClusters || c.isVisible()) {
                        c.draw(drawable);
                    }
                }
            }
        } catch (java.util.ConcurrentModificationException e) {
            // this is in case cluster list is modified by real time filter during rendering of clusters
            log.warning(e.getMessage());
        }
        gl.glPopMatrix();
    }


    public boolean isGrowMergedSizeEnabled() {
        return growMergedSizeEnabled;
    }

    /** Flags whether to grow the clusters when two clusters are merged, or to take the new size as the size of the older cluster.

    @param growMergedSizeEnabled true to grow the cluster size, false to use the older cluster's size.
     */
    public void setGrowMergedSizeEnabled(boolean growMergedSizeEnabled) {
        this.growMergedSizeEnabled = growMergedSizeEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.growMergedSizeEnabled", growMergedSizeEnabled);
    }

//    public float getVelocityMixingFactor() {
//        return velocityMixingFactor;
//    }
//
//    public void setVelocityMixingFactor(float velocityMixingFactor) {
//        if(velocityMixingFactor<0) velocityMixingFactor=0; if(velocityMixingFactor>1) velocityMixingFactor=1f;
//        this.velocityMixingFactor = velocityMixingFactor;
//        getPrefs().putFloat("RectangularClusterTracker.velocityMixingFactor",velocityMixingFactor);
//    }
    /** Use cluster velocityPPT to give clusters a kind of inertia, so that they
     * are virtually moved by their velocityPPT times the time between the last
     * event and the present one before updating cluster location.
     * Depends on enabling cluster paths. Setting this option true enables cluster paths.
     * @param useVelocity
     * @see #setPathsEnabled(boolean) 
     */
    public void setUseVelocity(boolean useVelocity) {
        if (useVelocity) {
            setPathsEnabled(true);
        }
        support.firePropertyChange("useVelocity", this.useVelocity, useVelocity);
        this.useVelocity = useVelocity;
        getPrefs().putBoolean("RectangularClusterTracker.useVelocity", useVelocity);
    }

    public boolean isUseVelocity() {
        return useVelocity;
    }

    public synchronized boolean isLogDataEnabled() {
        return logDataEnabled;
    }

    public synchronized void setLogDataEnabled(boolean logDataEnabled) {
        support.firePropertyChange("logDataEnabled", this.logDataEnabled, logDataEnabled);
        this.logDataEnabled = logDataEnabled;
        if (!logDataEnabled) {
            clusterLogger.close();
        } else {
            clusterLogger.open();
        }
    }

    public float getAspectRatio() {
        return aspectRatio;
    }

    public void setAspectRatio(float aspectRatio) {
        if (aspectRatio < 0) {
            aspectRatio = 0;
        } else if (aspectRatio > 4) {
            aspectRatio = 4;
        }
        this.aspectRatio = aspectRatio;
        getPrefs().putFloat("RectangularClusterTracker.aspectRatio", aspectRatio);

    }

    public float getMinAspectRatio() {
        return .25f;
    }

    public float getMaxAspectRatio() {
        return 4;
    }

//    public boolean isClassifierEnabled(){
//        return classifierEnabled;
//    }
//    /** Sets whether classifier is enabled.
//     * @param classifierEnabled true to enable classifier
//     */
//    public void setClassifierEnabled(boolean classifierEnabled){
//        this.classifierEnabled=classifierEnabled;
//        getPrefs().putBoolean("RectangularClusterTracker.classifierEnabled",classifierEnabled);
//    }
//    public float getClassifierThreshold(){
//        return classifierThreshold;
//    }
//
//    public void setClassifierThreshold(float classifierThreshold){
//        this.classifierThreshold=classifierThreshold;
//        getPrefs().putFloat("RectangularClusterTracker.classifierThreshold",classifierThreshold);
//    }
    public boolean isShowAllClusters() {
        return showAllClusters;
    }

    /**Sets annotation visibility of clusters that are not "visible"
     * @param showAllClusters true to show all clusters even if there are not "visible"
     */
    public void setShowAllClusters(boolean showAllClusters) {
        this.showAllClusters = showAllClusters;
        getPrefs().putBoolean("RectangularClusterTracker.showAllClusters", showAllClusters);
    }

    public boolean isDynamicAspectRatioEnabled() {
        return dynamicAspectRatioEnabled;
    }

    public void setDynamicAspectRatioEnabled(boolean dynamicAspectRatioEnabled) {
        this.dynamicAspectRatioEnabled = dynamicAspectRatioEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.dynamicAspectRatioEnabled", dynamicAspectRatioEnabled);
    }

    public boolean isUseNearestCluster() {
        return useNearestCluster;
    }

    public void setUseNearestCluster(boolean useNearestCluster) {
        this.useNearestCluster = useNearestCluster;
        getPrefs().putBoolean("RectangularClusterTracker.useNearestCluster", useNearestCluster);
    }

    public float getPredictiveVelocityFactor() {
        return predictiveVelocityFactor;
    }

    public void setPredictiveVelocityFactor(float predictiveVelocityFactor) {
        this.predictiveVelocityFactor = predictiveVelocityFactor;
    }

    public boolean isClusterLifetimeIncreasesWithAge() {
        return clusterLifetimeIncreasesWithAge;
    }

    /**
     * If true, cluster lifetime withtout support increases proportional to the age of the cluster relative to the clusterLifetimeWithoutSupportUs time
     */
    synchronized public void setClusterLifetimeIncreasesWithAge(boolean clusterLifetimeIncreasesWithAge) {
        this.clusterLifetimeIncreasesWithAge = clusterLifetimeIncreasesWithAge;
        getPrefs().putBoolean("RectangularClusterTracker.clusterLifetimeIncreasesWithAge", clusterLifetimeIncreasesWithAge);

    }

    public float getThresholdVelocityForVisibleCluster() {
        return thresholdVelocityForVisibleCluster;
    }

    /** A cluster must have at least this velocityPPT magnitude to become visible
     * @param thresholdVelocityForVisibleCluster speed in pixels/second
     */
    synchronized public void setThresholdVelocityForVisibleCluster(float thresholdVelocityForVisibleCluster) {
        if (thresholdVelocityForVisibleCluster < 0) {
            thresholdVelocityForVisibleCluster = 0;
        }
        this.thresholdVelocityForVisibleCluster = thresholdVelocityForVisibleCluster;
        getPrefs().putFloat("RectangularClusterTracker.thresholdVelocityForVisibleCluster", thresholdVelocityForVisibleCluster);
    }

    public int getPathLength() {
        return pathLength;
    }

    /** Sets the maximum number of path points recorded for each cluster. The {@link Cluster#path} list of points is adjusted
     * to be at most <code>pathLength</code> long.
     *
     * @param pathLength the number of recorded path points. If <2, set to 2.
     */
    synchronized public void setPathLength(int pathLength) {
        if (pathLength < 2) {
            pathLength = 2;
        }
        int old = this.pathLength;
        this.pathLength = pathLength;
        getPrefs().putInt("RectangularClusterTracker.pathLength", pathLength);
        support.firePropertyChange("pathLength", old, pathLength);
        if (velocityPoints > pathLength) {
            setVelocityPoints(pathLength);
        }
    }

    public boolean isDynamicAngleEnabled() {
        return dynamicAngleEnabled;
    }

    /** Setting dynamicAngleEnabled true enables variable-instantaneousAngle clusters. */
    synchronized public void setDynamicAngleEnabled(boolean dynamicAngleEnabled) {
        this.dynamicAngleEnabled = dynamicAngleEnabled;
        getPrefs().putBoolean("RectangularClusterTracker.dynamicAngleEnabled", dynamicAngleEnabled);
    }

    public float getVelocityTauMs() {
        return velocityTauMs;
    }

    synchronized public void setVelocityTauMs(float velocityTauMs) {
        this.velocityTauMs = velocityTauMs;
        getPrefs().putFloat("RectangularClusterTracker.velocityTauMs", velocityTauMs);
        for (Cluster c : clusters) {
            c.vxFilter.setTauMs(velocityTauMs);
            c.vyFilter.setTauMs(velocityTauMs);
        }

    }

    /** @see #setVelocityPoints(int)
     *
     * @return number of points used to estimate velocityPPT.
     */
    public int getVelocityPoints() {
        return velocityPoints;
    }

    /** Sets the number of path points to use to estimate cluster velocityPPT.
     *
     * @param velocityPoints the number of points to use to estimate velocityPPT.
     * Bounded above to number of path points that are stored.
     * @see #setPathLength(int)
     * @see #setPathsEnabled(boolean)
     */
    public void setVelocityPoints(int velocityPoints) {
        if (velocityPoints >= pathLength) {
            velocityPoints = pathLength;
        }
        int old = this.velocityPoints;
        this.velocityPoints = velocityPoints;
        getPrefs().putInt("RectangularClusterTracker.velocityPoints", velocityPoints);
        support.firePropertyChange("velocityPoints", old, this.velocityPoints);
    }

    /**
     * @return the velAngDiffDegToNotMerge
     */
    public float getVelAngDiffDegToNotMerge() {
        return velAngDiffDegToNotMerge;
    }

    /**
     * @param velAngDiffDegToNotMerge the velAngDiffDegToNotMerge to set
     */
    public void setVelAngDiffDegToNotMerge(float velAngDiffDegToNotMerge) {
        if (velAngDiffDegToNotMerge < 0) {
            velAngDiffDegToNotMerge = 0;
        } else if (velAngDiffDegToNotMerge > 180) {
            velAngDiffDegToNotMerge = 180;
        }
        this.velAngDiffDegToNotMerge = velAngDiffDegToNotMerge;
        getPrefs().putFloat("RectangularClusterTracker.velAngDiffDegToNotMerge", velAngDiffDegToNotMerge);
    }

    /**
     * @return the showClusterNumber
     */
    public boolean isShowClusterNumber() {
        return showClusterNumber;
    }

    /**
     * @param showClusterNumber the showClusterNumber to set
     */
    public void setShowClusterNumber(boolean showClusterNumber) {
        this.showClusterNumber = showClusterNumber;
        getPrefs().putBoolean("RectangularClusterTracker.showClusterNumber", showClusterNumber);
    }

    /**
     * @return the showClusterEps
     */
    public boolean isShowClusterEps() {
        return showClusterEps;
    }

    /**
     * @param showClusterEps the showClusterEps to set
     */
    public void setShowClusterEps(boolean showClusterEps) {
        this.showClusterEps = showClusterEps;
        getPrefs().putBoolean("RectangularClusterTracker.showClusterEps", showClusterEps);
    }

    /**
     * @return the showClusterVelocity
     */
    public boolean isShowClusterVelocity() {
        return showClusterVelocity;
    }

    /**
     * @param showClusterVelocity the showClusterVelocity to set
     */
    public void setShowClusterVelocity(boolean showClusterVelocity) {
        this.showClusterVelocity = showClusterVelocity;
        getPrefs().putBoolean("RectangularClusterTracker.showClusterVelocity", showClusterVelocity);
    }

    /**
     * @return the velocityVectorScaling
     */
    public float getVelocityVectorScaling() {
        return velocityVectorScaling;
    }

    /**
     * @param velocityVectorScaling the velocityVectorScaling to set
     */
    public void setVelocityVectorScaling(float velocityVectorScaling) {
        this.velocityVectorScaling = velocityVectorScaling;
        getPrefs().putFloat("RectangularClusterTracker.velocityVectorScaling", velocityVectorScaling);
    }

    // replaced by assignment from updateIntervalMs*1000
//    /**
//     * @return the loggingIntervalUs
//     */
//    public int getLoggingIntervalUs() {
//        return loggingIntervalUs;
//    }
//
//    /**
//     * @param loggingIntervalUs the loggingIntervalUs to set
//     */
//    public void setLoggingIntervalUs(int loggingIntervalUs) {
//        this.loggingIntervalUs = loggingIntervalUs;
//        getPrefs().putInt("RectangularClusterTracker.loggingIntervalUs", loggingIntervalUs);
//    }
    /**
     * @return the initializeVelocityToAverage
     */
    public boolean isInitializeVelocityToAverage() {
        return initializeVelocityToAverage;
    }

    /**
     * @param initializeVelocityToAverage the initializeVelocityToAverage to set
     */
    public void setInitializeVelocityToAverage(boolean initializeVelocityToAverage) {
        this.initializeVelocityToAverage = initializeVelocityToAverage;
        getPrefs().putBoolean("RectangularClusterTracker.initializeVelocityToAverage", initializeVelocityToAverage);
    }

    /**
     * @return the showClusterMass
     */
    public boolean isShowClusterMass() {
        return showClusterMass;
    }

    /**
     * @param showClusterMass the showClusterMass to set
     */
    public void setShowClusterMass(boolean showClusterMass) {
        this.showClusterMass = showClusterMass;
        getPrefs().putBoolean("RectangularClusterTracker.showClusterMass", showClusterMass);
    }

    /**
     * @return the clusterLoggingMethod
     */
    public ClusterLoggingMethod getClusterLoggingMethod() {
        return clusterLoggingMethod;
    }

    /**
     * @param clusterLoggingMethod the clusterLoggingMethod to set
     */
    public void setClusterLoggingMethod(ClusterLoggingMethod clusterLoggingMethod) {
        if (isLogDataEnabled()) {
            log.warning("changing logging method during logging not allowed");
            support.firePropertyChange("clusterLoggingMethod", null, getClusterLoggingMethod());
            return;
        }
        ClusterLoggingMethod old = this.clusterLoggingMethod;
        this.clusterLoggingMethod = clusterLoggingMethod;
        support.firePropertyChange("clusterLoggingMethod", old, clusterLoggingMethod);
        getPrefs().put("RectangularClusterTracker.clusterLoggingMethod", clusterLoggingMethod.toString());
    }

    /**
     * @return the surroundInhibitionEnabled
     */
    public boolean isSurroundInhibitionEnabled (){
        return surroundInhibitionEnabled;
    }

    /**
     * @param surroundInhibitionEnabled the surroundInhibitionEnabled to set
     */
    public void setSurroundInhibitionEnabled (boolean surroundInhibitionEnabled){
        boolean old=this.surroundInhibitionEnabled;
        this.surroundInhibitionEnabled = surroundInhibitionEnabled;
        prefs().putBoolean("RectangularClusterTracker.surroundInhibitionEnabled",surroundInhibitionEnabled);
        support.firePropertyChange("surroundInhibitionEnabled",old,surroundInhibitionEnabled);
    }
}
