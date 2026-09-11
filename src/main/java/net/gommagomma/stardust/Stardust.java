package net.gommagomma.stardust;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.swing.JFrame;
import javax.swing.Timer;

import net.gommagomma.stardust.io.RunLogger;
import net.gommagomma.stardust.io.Savepoint;
import net.gommagomma.stardust.io.SimulationPaths;
import net.gommagomma.stardust.math.Vector3D;
import net.gommagomma.stardust.model.Particle;
import net.gommagomma.stardust.ui.RenderActionListener;
import net.gommagomma.stardust.ui.SimulationPanel;


public class Stardust
{
    public static final String WINDOW_TITLE = "Stardust — Accrescimento Gravitazionale Planetesimale";

    private final SimulationPaths paths;
    private final SimulationParams params;
    private final RunLogger eventsLogger;
    private final RunLogger runsLogger;
    private SimulationEngine engine;
    private Thread engineThread;
    private List<Particle> particles;
    private final String windowTitle;


    public static void main(String[] args)
    throws Exception
    {
    	String simulationId = (args.length > 0 && !args[0].isBlank())
                ? args[0]
                : "disk-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        Stardust stardust = new Stardust(simulationId);
        stardust.setParticles(createProtoplanetaryDisk(stardust.getContext()));
        stardust.start();
    }


    public Stardust(String simulationId)
    throws IOException
    {
        this(simulationId, WINDOW_TITLE);
    }

    public Stardust(String simulationId, String windowTitle)
    throws IOException
    {
        this.paths = new SimulationPaths(simulationId);
        this.params = new SimulationParams(paths);
        this.eventsLogger = new RunLogger(paths.eventsLogFile);
        this.runsLogger = new RunLogger(paths.runsLogFile);
        this.windowTitle = windowTitle;
    }

    public SimulationParams getContext() {
        return params;
    }
    public void setParticles(List<Particle> particles) {
        this.particles = particles;
    }

    public void start()
    {
        engine = initEngine();
        runsLogger.log(String.format("START wall=%s simTime=%.1f", LocalDateTime.now(), engine.getMetrics().getSimulationTime()));
        engineThread = startEngineThread();
        SimulationPanel panel = new SimulationPanel(engine, params);
        JFrame frame = setupWindow(panel);

        startRenderLoop(frame, panel);
        startAutosaveLoop();
    }

    private SimulationEngine initEngine()
    {
        if (Savepoint.exists(paths.savepointFile.toString())) {
            try {
                Savepoint.SavepointState state = Savepoint.load(paths.savepointFile.toString());
                System.out.println("Savepoint ripristinato.");
                return new SimulationEngine(state.particles, eventsLogger, state.metrics, params);
            } catch (IOException e) {
                System.err.println("Impossibile ripristinare il savepoint (" + e.getMessage() + "), generazione di un nuovo disco.");
            }
        } else {
            System.out.println("Nessun savepoint trovato, generazione di un nuovo disco.");
        }

        if (particles == null) {
            throw new IllegalStateException("Nessuna lista di particelle impostata.");
        }

        return new SimulationEngine(particles, eventsLogger, params);
    }

    private Thread startEngineThread()
    {
        Thread physicsThread = new Thread(engine::run, "engine-thread");
        physicsThread.setDaemon(true);
        physicsThread.start();
        return physicsThread;
    }

    private JFrame setupWindow(SimulationPanel panel)
    {
        JFrame frame = new JFrame(windowTitle);
        frame.add(panel);
        frame.setSize(800, 800);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.out.println("Chiusura richiesta: stop dell'engine e salvataggio del savepoint...");
                engine.stop();
                try {
                    engineThread.join(10000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                saveSavepoint();
                runsLogger.log(String.format("STOP  wall=%s simTime=%.1f", LocalDateTime.now(), engine.getMetrics().getSimulationTime()));          
                System.exit(0);
            }
        });

        frame.setFocusable(true);
        frame.setVisible(true);
        return frame;
    }

    private void startRenderLoop(JFrame frame, SimulationPanel panel)
    {
        RenderActionListener listener = new RenderActionListener(frame, windowTitle, panel, engine, paths, params);
        Timer renderTimer = new Timer(1000 / params.fps, listener);
        renderTimer.start();
    }

    private void startAutosaveLoop()
    {
        if (params.autosaveInterval > 0) {
            Timer autosaveTimer = new Timer(params.autosaveInterval * 1000, e ->
                new Thread(this::saveSavepoint, "autosave-savepoint-thread").start());
            autosaveTimer.start();
        }
    }

    private void saveSavepoint()
    {
        try {
            Savepoint.save(paths.savepointFile.toString(), engine);
        } catch (IOException e) {
            System.err.println("Errore nel salvataggio del savepoint: " + e.getMessage());
        }
    }

    // Generazione del disco
    private static List<Particle> createProtoplanetaryDisk(SimulationParams params)
    {
        List<Particle> particles = new ArrayList<>();
        Random rnd = new Random();

        //particles.add(createProtoplanet(0.4, Math.PI, 1e25, 0.0, 3000.0));

        double exp = 1.0 - params.massPowerLawIndex;
        double mMinExp = Math.pow(params.initialParticleMassMin, exp);
        double mMaxExp = Math.pow(params.initialParticleMassMax, exp);
        double r2Min = params.diskInnerRadius * params.diskInnerRadius;
        double r2Max = params.diskOuterRadius * params.diskOuterRadius;

        for (int ii = 0; ii < params.n; ii++)
        {
            double r = Math.sqrt(r2Min + rnd.nextDouble() * (r2Max - r2Min));
            double theta = rnd.nextDouble() * 2 * Math.PI;

            double x = r * Math.cos(theta);
            double y = r * Math.sin(theta);
            double z = (rnd.nextDouble() - 0.5) * r * 0.01;

            double v = Math.sqrt(PhysicsConstants.G * params.centralStarMass / r);
            double vx = -v * Math.sin(theta);
            double vy =  v * Math.cos(theta);
            double vz = rnd.nextDouble() - 0.5;

            double dispersion = params.initialVelocityDispersion * v;
            vx += (rnd.nextDouble() - 0.5) * dispersion;
            vy += (rnd.nextDouble() - 0.5) * dispersion;
            vz *= dispersion;

            double u = rnd.nextDouble();
            double mass = Math.pow(mMinExp + u * (mMaxExp - mMinExp), 1.0 / exp);
            double charge = (rnd.nextBoolean() ? 1 : -1) * rnd.nextDouble() * params.initialMaxCharge;

            particles.add(new Particle(new Vector3D(x, y, z), new Vector3D(vx, vy, vz), mass, charge, params.initialParticleDensity));
        }

        return particles;
    }

    public static Particle createProtoplanet(SimulationParams params, double rAU, double theta, double mass, double charge, double density)
    {
        double r = rAU * PhysicsConstants.AU;
        Vector3D position = new Vector3D(r * Math.cos(theta), r * Math.sin(theta), 0.0);

        double v = Math.sqrt(PhysicsConstants.G * params.centralStarMass / r);
        Vector3D velocity = new Vector3D(-v * Math.sin(theta), v * Math.cos(theta), 0.0);

        return new Particle(position, velocity, mass, charge, density);
    }
}