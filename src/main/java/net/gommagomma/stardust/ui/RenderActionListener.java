package net.gommagomma.stardust.ui;


import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.JFrame;
import net.gommagomma.stardust.core.SimulationConfig;
import net.gommagomma.stardust.core.SimulationEngine;


public class RenderActionListener
implements ActionListener
{
    private final JFrame frame;
    private final String baseTitle;

    private final SimulationPanel panel;
    private final SimulationEngine engine;

    private long lastScreenshotStep = -1;
    private long lastFpsCheckTime = System.currentTimeMillis();
    private int frameCount = 0;


    public RenderActionListener(JFrame frame, String baseTitle, SimulationPanel panel, SimulationEngine engine)
    {
        this.frame = frame;
        this.baseTitle = baseTitle;
        this.panel = panel;
        this.engine = engine;
    }


    @Override
    public void actionPerformed(ActionEvent e)
    {
        // Update "vbeloce" dello snapshot grafico e repaint
        synchronized (engine.getParticles()) {
            panel.updateSnapshot(engine.getParticles(), engine.getSimulationTime(), SimulationConfig.DT);
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
        long currentStep = engine.getStepCount();
        if (currentStep == 0 || (currentStep > 0 && currentStep % 10000 == 0 && currentStep != lastScreenshotStep)) {
            lastScreenshotStep = currentStep;
            File file = new File(new File("screenshots"), String.format("screenshot_%s_t%09d.png", SimulationConfig.SESSION_ID, (long) engine.getSimulationTime()));
            panel.saveScreenshot(file);
        }
    }
}