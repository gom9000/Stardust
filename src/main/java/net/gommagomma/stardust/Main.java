package net.gommagomma.stardust;


import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.swing.JFrame;
import javax.swing.Timer;

import net.gommagomma.stardust.core.Particle;
import net.gommagomma.stardust.core.SimulationConfig;
import net.gommagomma.stardust.core.SimulationEngine;
import net.gommagomma.stardust.core.Vector3D;
import net.gommagomma.stardust.io.Savepoint;
import net.gommagomma.stardust.ui.RenderActionListener;
import net.gommagomma.stardust.ui.SimulationPanel;


public class Main
{
	public static final String WINDOW_TITLE = "Simulazione Accrezione Polveri Protoplanetarie";


    public static void main(String[] args)
    {
        SimulationPanel panel = new SimulationPanel();
        SimulationEngine engine = initEngine();
        Thread engineThread = startEngineThread(engine);
        JFrame frame = setupWindow(panel, engine, engineThread);

        startRenderLoop(frame, panel, engine, SimulationConfig.FPS);
        startAutosaveLoop(engine);
    }


    /**
     * Inizializza l'engine tentando il ripristino da Savepoint o generando un nuovo disco.
     */
    private static SimulationEngine initEngine()
    {
        if (Savepoint.exists(SimulationConfig.SAVEPOINT_FILE)) {
            try {
                Savepoint.SavepointState state = Savepoint.load(SimulationConfig.SAVEPOINT_FILE);
                System.out.println("Savepoint ripristinato.");
                return new SimulationEngine(state.particles, state.simulationTime, state.stepCount, state.totalMerges, state.totalBounces, state.totalEscapes, state.totalStarFalls, state.totalFragmentations);
            } catch (IOException e) {
                System.err.println("Impossibile ripristinare il savepoint (" + e.getMessage() + "), generazione di un nuovo disco.");
            }
        } else {
            System.out.println("Nessun savepoint trovato, generazione di un nuovo disco.");
        }

        List<Particle> particles = createProtoplanetaryDisk();

        return new SimulationEngine(particles);
    }


    /**
     * Avvia il thread dell'engine.
     * 
     * @param engine Il motore di simulazione fisica da avviare
     */
    private static Thread startEngineThread(SimulationEngine engine)
    {
        Thread physicsThread = new Thread(engine::run, "engine-thread");
        physicsThread.setDaemon(true);
        physicsThread.start();

        return physicsThread;
    }


    /**
     * Configura la finestra e lega la chiusura col salvataggio dello stato.
     * 
     * @param panel  Il pannello grafico da visualizzare 
     * @param engine Il motore di simulazione fisica da fermare
     * @param engineThread  Il thread del motore da chiudere
     */
    private static JFrame setupWindow(SimulationPanel panel, SimulationEngine engine, Thread engineThread)
    {
        JFrame frame = new JFrame(WINDOW_TITLE);
        frame.add(panel);
        frame.setSize(800, 800);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.out.println("Chiusura richiesta: stop dell'engine e salvataggio del savepoint...");
                engine.stop();
                saveSavepoint(engine);
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


    /**
     * Timer per l'aggiornamento della grafica.
     * 
     * @param panel  Il pannello grafico da aggiornare
     * @param engine Il motore di simulazione fisica da cui leggere lo stato
     * @param fps    I frame al secondo desiderati
     */
    private static void startRenderLoop(JFrame frame, SimulationPanel panel, SimulationEngine engine, int fps)
    {
        RenderActionListener listener = new RenderActionListener(frame, WINDOW_TITLE, panel, engine);
        Timer renderTimer = new Timer(1000 / fps, listener);
        renderTimer.start();
    }


    /**
     * Timer di autosalvataggio asincrono in background.
     * 
     * @param engine Il motore di simulazione fisica da cui leggere lo stato da salvare
     */
    private static void startAutosaveLoop(SimulationEngine engine)
    {
        if (SimulationConfig.AUTOSAVE_INTERVAL_SECONDS > 0) {
            Timer autosaveTimer = new Timer(SimulationConfig.AUTOSAVE_INTERVAL_SECONDS * 1000, e -> {
                new Thread(() -> saveSavepoint(engine), "autosave-savepoint-thread").start();
            });
            autosaveTimer.start();
        }
    }


    /**
     * Helper per la scrittura fisica su disco del Savepoint.
     */
    private static void saveSavepoint(SimulationEngine engine)
    {
        try {
            Savepoint.save(SimulationConfig.SAVEPOINT_FILE, engine);
        } catch (IOException e) {
            System.err.println("Errore nel salvataggio del savepoint: " + e.getMessage());
        }
    }


    /**
     * Generazione del disco protoplanetario.
     */
    private static List<Particle> createProtoplanetaryDisk()
    {
        List<Particle> particles = new ArrayList<>();
        Random rnd = new Random();

        // 1. INSERIMENTO PROTOPIANETI (Prenderanno ID #0, #1...)
        // particles.add(createProtoplanet(0.4, Math.PI, 1e29, 0.0, 3000.0));

        // 2. GENERAZIONE POLVERI
        double exp = 1.0 - SimulationConfig.MASS_POWER_LAW_INDEX;
        double mMinExp = Math.pow(SimulationConfig.BASE_PARTICLE_MASS_MIN, exp);
        double mMaxExp = Math.pow(SimulationConfig.BASE_PARTICLE_MASS_MAX, exp);

        double r2Min = SimulationConfig.DISK_INNER_RADIUS * SimulationConfig.DISK_INNER_RADIUS;
        double r2Max = SimulationConfig.DISK_OUTER_RADIUS * SimulationConfig.DISK_OUTER_RADIUS;

        for (int ii = 0; ii < SimulationConfig.N; ii++)
        {
            double r = Math.sqrt(r2Min + rnd.nextDouble() * (r2Max - r2Min));
            double theta = rnd.nextDouble() * 2 * Math.PI;

            double x = r * Math.cos(theta);
            double y = r * Math.sin(theta);
            double z = (rnd.nextDouble() - 0.5) * r * 0.01;

            double v = Math.sqrt(SimulationConfig.G * SimulationConfig.STAR_MASS / r);
            double vx = -v * Math.sin(theta);
            double vy =  v * Math.cos(theta);
            double vz = rnd.nextDouble() - 0.5;

            double dispersion = SimulationConfig.INITIAL_VELOCITY_DISPERSION * v; 
            vx += (rnd.nextDouble() - 0.5) * dispersion;
            vy += (rnd.nextDouble() - 0.5) * dispersion;
            vz *= dispersion;

            Vector3D position = new Vector3D(x, y, z);
            Vector3D velocity = new Vector3D(vx, vy, vz);

            double u = rnd.nextDouble(); 
            double mass = Math.pow(mMinExp + u * (mMaxExp - mMinExp), 1.0 / exp);
            double charge = (rnd.nextBoolean() ? 1 : -1) * rnd.nextDouble() * SimulationConfig.MAX_INITIAL_CHARGE;

            particles.add(new Particle(position, velocity, mass, charge, SimulationConfig.INITIAL_DUST_DENSITY));
        }

        return particles;
    }


    /**
     * Factory method per definire protopianeti in orbita circolare kepleriana.
     */
    private static Particle createProtoplanet(double rAU, double theta, double mass, double charge, double density)
    {
        double r = rAU * SimulationConfig.AU;
        Vector3D position = new Vector3D(r * Math.cos(theta), r * Math.sin(theta), 0.0);

        double v = Math.sqrt(SimulationConfig.G * SimulationConfig.STAR_MASS / r);
        Vector3D velocity = new Vector3D(-v * Math.sin(theta), v * Math.cos(theta), 0.0);

        return new Particle(position, velocity, mass, charge, density);
    }
}