package net.gommagomma.stardust.ui;


import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.JFrame;

import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.SimulationEngine;
import net.gommagomma.stardust.io.SimulationPaths;


public class RenderActionListener
implements ActionListener
{
    private final JFrame frame;
    private final String baseTitle;

    private final SimulationPanel panel;
    private final SimulationEngine engine;
    private final SimulationPaths paths;

    private long lastScreenshotStep = -1;
    private long lastFpsCheckTime = System.currentTimeMillis();
    private int frameCount = 0;


    public RenderActionListener(JFrame frame, String baseTitle, SimulationPanel panel, SimulationEngine engine, SimulationPaths paths)
    {
        this.frame = frame;
        this.baseTitle = baseTitle;
        this.panel = panel;
        this.engine = engine;
        this.paths = paths;
    }


    @Override
    public void actionPerformed(ActionEvent e)
    {
        // Update "vbeloce" dello snapshot grafico e repaint
        synchronized (engine.getParticles()) {
            panel.updateSnapshot(engine.getParticles(), engine.getMetrics().getSimulationTime(), SimulationConfig.DT);
        }
        panel.repaint();

        // Calcolo FPS reali e aggiornamento del Titolo della Finestra ogni secondo
        frameCount++;
        long now = System.currentTimeMillis();
        long elapsed = now - lastFpsCheckTime;

        if (elapsed >= 1000) {
            double fps = (frameCount * 1000.0) / elapsed;
            frame.setTitle(String.format("%s (FPS: %.1f | TPS: %.1f)", baseTitle, fps, engine.getCurrentTPS()));

            frameCount = 0;
            lastFpsCheckTime = now;
        }

        // Salvataggio Screenshot della simulazione
        long currentStep = engine.getMetrics().getStepCount();
        if (currentStep == 0 || (currentStep > 0 && currentStep % 1500 == 0 && currentStep != lastScreenshotStep)) {
            lastScreenshotStep = currentStep;
            File file = new File(paths.screenshotsDir.toString(), String.format("screenshot_t%d.png", (long) engine.getMetrics().getSimulationTime()));
            panel.saveScreenshot(file);
        }
    }
}