package net.gommagomma.stardust.demo;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.Timer;

import net.gommagomma.stardust.SimulationConfig;
import net.gommagomma.stardust.SimulationEngine;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.ui.RenderActionListener;
import net.gommagomma.stardust.ui.SimulationPanel;


public class HieearchicalSystem
{
	public static final String WINDOW_TITLE = "Stardust — Sistema Gerarchico";


    public static void main(String[] args)
    {
        SimulationEngine engine = initEngine();
        Thread engineThread = startEngineThread(engine);
        SimulationPanel panel = new SimulationPanel(engine);
        JFrame frame = setupWindow(panel, engine, engineThread);

        startRenderLoop(frame, panel, engine, SimulationConfig.FPS);
    }


    private static SimulationEngine initEngine()
    {
        List<Particle> particles = createHierarchicalSystem();

        return new SimulationEngine(particles);
    }

    private static Thread startEngineThread(SimulationEngine engine)
    {
        Thread physicsThread = new Thread(engine::run, "engine-thread");
        physicsThread.setDaemon(true);
        physicsThread.start();

        return physicsThread;
    }

    private static JFrame setupWindow(SimulationPanel panel, SimulationEngine engine, Thread engineThread)
    {
        JFrame frame = new JFrame(WINDOW_TITLE);
        frame.add(panel);
        frame.setSize(800, 800);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                engine.stop();
                try {
                	engineThread.join(10000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.exit(0);
            }
        });

        frame.setFocusable(true);
        frame.setVisible(true);

        return frame;
    }

    private static void startRenderLoop(JFrame frame, SimulationPanel panel, SimulationEngine engine, int fps)
    {
        RenderActionListener listener = new RenderActionListener(frame, WINDOW_TITLE, panel, engine);
        Timer renderTimer = new Timer(1000 / fps, listener);
        renderTimer.start();
    }

    private static List<Particle> createHierarchicalSystem()
    {
        List<Particle> particles = new ArrayList<>();
        
        // 2. Pianeta in orbita attorno alla stella (es. a 1 AU)
        double starPlanetDist = 1.0 * SimulationConfig.AU;
        double vPlanet = Math.sqrt(SimulationConfig.G * SimulationConfig.STAR_MASS / starPlanetDist);
        Particle planet = new Particle(
                new Vector3D(starPlanetDist, 0, 0),
                new Vector3D(0, vPlanet, 0),
                5.972e24, 0.0, 5500.0
        );

        // 3. Satellite in orbita attorno al pianeta (es. a 400.000 km)
        double planetMoonDist = 4.0e8;
        double vMoonRel = Math.sqrt(SimulationConfig.G * planet.getMass() / planetMoonDist);
        Particle satellite = new Particle(
                new Vector3D(starPlanetDist + planetMoonDist, 0, 0),
                new Vector3D(0, vPlanet + vMoonRel, 0),
                7.342e22, 0.0, 3340.0
        );

        particles.add(planet);
        particles.add(satellite);

        return particles;
    }
}