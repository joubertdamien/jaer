/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.sf.jaer.eventprocessing.filter;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.util.awt.TextRenderer;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.Iterator;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileFilter;
import net.sf.jaer.Description;
import net.sf.jaer.DevelopmentStatus;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.ApsDvsEventPacket;
import net.sf.jaer.event.BasicEvent;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.eventprocessing.EventFilter2D;
import net.sf.jaer.graphics.AEViewer;
import net.sf.jaer.graphics.AEViewer.PlayMode;
import net.sf.jaer.graphics.FrameAnnotater;
import net.sf.jaer.util.TobiLogger;

/**
 * Measures and adjusts timestamps of events using the computer clock as the
 * time base.
 *
 * @author Tobi
 */
@Description("Measures and adjusts timestamps of events using the computer clock as the time base")
@DevelopmentStatus(DevelopmentStatus.Status.Experimental)
public class TimestampCalibrator extends EventFilter2D implements FrameAnnotater, PropertyChangeListener {

    private float ppmTimestampTooFastError = getFloat("ppmTimestampTooFastError", 0); // parts per million that the timestamp tick is too fast
    private long driftTsMinusClockMs = 0;
    private boolean resetPending = true;
    private long startingClockNs = 0, startingTsUs = 0;
    private int minTimeForCalibrationS = getInt("minTimeForCalibrationS", 30);
    private boolean enableCalibration = false;
    private boolean correctTimestampEnabled = getBoolean("correctTimestampEnabled", true);
    int bigWraps = 0, lastPacketTs = Integer.MIN_VALUE; // number of times camera timestamp has wrapped around
    boolean propertyChangeListenersAdded = false;

    private String lastLoggingFolder = getString("lastLoggingFolder", System.getProperty("user.home"));
    private int loggingIntervalS = getInt("loggingIntervalS", 1);
    private TobiLogger tobiLogger = null;
    private long lastLoggingTimeMs=0;

    public TimestampCalibrator(AEChip chip) {
        super(chip);
        setPropertyTooltip("minTimeForCalibrationS", "minimum time elapsed before calibration is updated");
        setPropertyTooltip("enableCalibration", "a new calibration value is not computed unless enabled, allowing us of filter with pre-measured calibration");
        setPropertyTooltip("ppmTimestampTooFastError", "<html>the calibration factor: <br>parts per million that the timestamp clock is too fast relative to computer clock. <br> I.e. +1000 means timestamps advance 0.1% too fast; <br>after 1000 ticks of computer clock, timestamp will be 1 tick ahead");
        setPropertyTooltip("correctTimestampEnabled", "if enabled, the event timestamps are corrected by multiplying by ppmTimestampTooFastError");
        setPropertyTooltip("selectLoggingFolder", "selects folder to store timestamp calibration log file");
        setPropertyTooltip("loggingIntervalS", "interval in seconds for logging calibration data after selecting logging folder");
    }

    @Override
    synchronized public EventPacket<?> filterPacket(EventPacket<?> in) {
        if (in.isEmpty() || !(chip.getAeViewer().getPlayMode() == PlayMode.LIVE)) {
            return in;
        }
        if (!propertyChangeListenersAdded) {
            propertyChangeListenersAdded = true;
            chip.getAeViewer().addPropertyChangeListener(AEViewer.EVENT_TIMESTAMPS_RESET, this);
        }
        int currentTs = in.getLastTimestamp();
        long currentClockNs = System.nanoTime();
        if (currentTs < lastPacketTs && !resetPending) {
            bigWraps++;
            log.info("timestamp has wrapped around " + bigWraps + " times");
        }
        if (resetPending) {
            startingClockNs = currentClockNs;
            startingTsUs = currentTs;
            bigWraps = 0;
            resetPending = false;
        }
        lastPacketTs = currentTs;
        long dtClockNs = currentClockNs - startingClockNs;
        long dtTsUs = (currentTs - startingTsUs) + bigWraps * ((long) Integer.MAX_VALUE - (long) Integer.MIN_VALUE);
        if (dtClockNs / 1000000000 < minTimeForCalibrationS) {
            return in;
        }

        driftTsMinusClockMs = dtTsUs / 1000 - dtClockNs / 1000000;
        float ratioTsToClock = (float) dtTsUs * 1000 / (float) dtClockNs;
        float ppmTsTooFast = 1e6f * (ratioTsToClock - 1);
        if (enableCalibration) {
            setPpmTimestampTooFastError(ppmTsTooFast);
        }
       if(tobiLogger!=null && tobiLogger.isEnabled() && (System.currentTimeMillis()-lastLoggingTimeMs) > 1000*loggingIntervalS){
            lastLoggingTimeMs=System.currentTimeMillis();
            tobiLogger.log(String.format("%d %d",dtClockNs,dtTsUs));
        }
        if (correctTimestampEnabled) {
            Iterator<BasicEvent> i = null;
            if (in instanceof ApsDvsEventPacket) {
                i = ((ApsDvsEventPacket) in).fullIterator();
            } else {
                i = (Iterator<BasicEvent>) in.inputIterator();
            }
            while (i.hasNext()) {
                BasicEvent e = i.next();
                e.timestamp = (int) (e.timestamp * (1 - 1e-6f * ppmTimestampTooFastError)); // if ppmTimestampTooFastError is positive, timestamp will be reduced
            }
        }
        return in;
    }

    @Override
    public void resetFilter() {
        driftTsMinusClockMs = 0;
        resetPending = true;
        bigWraps = 0;
    }

    @Override
    public void initFilter() {
    }

    TextRenderer textRenderer = null;

    @Override
    public void annotate(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();
//             if (textRenderer == null) {
        textRenderer = new TextRenderer(new Font("SansSerif", Font.PLAIN, 24), true, true);
        textRenderer.setColor(Color.blue);

//            }
        gl.glColor3f(0, 0, 1);
        gl.glLineWidth(2);
        final float x = chip.getSizeX() * .3f, y = (chip.getSizeY()) * .9f, scale = .2f;
        textRenderer.begin3DRendering();
        String s = String.format("TimestampCalibrator: ts-wall: %6dms, ppmTsTooFast: %6.0f", driftTsMinusClockMs, ppmTimestampTooFastError);
        Rectangle2D r = textRenderer.getBounds(s);
        textRenderer.draw3D(s, (float) (x - scale * r.getWidth() / 2), (float) (y - scale * r.getHeight() / 2), 0, scale);
        textRenderer.end3DRendering();
    }

    /**
     * @return the ppmTimestampTooFastError
     */
    public float getPpmTimestampTooFastError() {
        return ppmTimestampTooFastError;
    }

    /**
     * @param ppmTimestampTooFastError the ppmTimestampTooFastError to set
     */
    public void setPpmTimestampTooFastError(float ppmTimestampTooFastError) {
        float old = this.ppmTimestampTooFastError;
        this.ppmTimestampTooFastError = ppmTimestampTooFastError;
        getSupport().firePropertyChange("ppmTimestampTooFastError", old, this.ppmTimestampTooFastError);
        putFloat("ppmTimestampTooFastError", ppmTimestampTooFastError);
    }

    @Override
    public void propertyChange(PropertyChangeEvent pce) {
        if (pce.getPropertyName() == AEViewer.EVENT_TIMESTAMPS_RESET) {
            resetFilter();
            log.info("timestamps reset: resetting timestamp calibration");
        }
    }

    synchronized public void doSelectLoggingFolder() {
        JFileChooser c = new JFileChooser(lastLoggingFolder);
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int ret = c.showDialog(chip.getAeViewer().getFilterFrame(), "Select folder");
        if (ret != JFileChooser.APPROVE_OPTION) {
            return;
        }
        lastLoggingFolder = c.getSelectedFile().getPath();
        putString("lastLoggingFolder", lastLoggingFolder);
        tobiLogger= new TobiLogger(lastLoggingFolder+File.separator+"TimestampCalibrator","SystemTimeMillis dtClockNs dtTsUs");
        tobiLogger.setAbsoluteTimeEnabled(true);
        tobiLogger.setEnabled(true);// creates log file
        tobiLogger.addComment("timestamp calibrator log file");
        
    }

    /**
     * @return the minTimeForCalibrationS
     */
    public int getMinTimeForCalibrationS() {
        return minTimeForCalibrationS;
    }

    /**
     * @param minTimeForCalibrationS the minTimeForCalibrationS to set
     */
    public void setMinTimeForCalibrationS(int minTimeForCalibrationS) {
        this.minTimeForCalibrationS = minTimeForCalibrationS;
        putInt("minTimeForCalibrationS", minTimeForCalibrationS);
    }

    /**
     * @return the enableCalibration
     */
    public boolean isEnableCalibration() {
        return enableCalibration;
    }

    /**
     * @param enableCalibration the enableCalibration to set
     */
    public void setEnableCalibration(boolean enableCalibration) {
        this.enableCalibration = enableCalibration;
    }

    /**
     * @return the correctTimestampEnabled
     */
    public boolean isCorrectTimestampEnabled() {
        return correctTimestampEnabled;
    }

    /**
     * @param correctTimestampEnabled the correctTimestampEnabled to set
     */
    public void setCorrectTimestampEnabled(boolean correctTimestampEnabled) {
        this.correctTimestampEnabled = correctTimestampEnabled;
        putBoolean("correctTimestampEnabled", correctTimestampEnabled);
    }

    /**
     * @return the loggingIntervalS
     */
    public int getLoggingIntervalS() {
        return loggingIntervalS;
    }

    /**
     * @param loggingIntervalS the loggingIntervalS to set
     */
    public void setLoggingIntervalS(int loggingIntervalS) {
        this.loggingIntervalS = loggingIntervalS;
        putInt("loggingIntervalS",loggingIntervalS);
    }

}